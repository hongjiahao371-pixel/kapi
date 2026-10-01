package merge

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"go/parser"
	"go/token"
)

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

// fields converts a Go map into wire-shaped field values.
func fields(kv map[string]any) map[string]json.RawMessage {
	out := make(map[string]json.RawMessage, len(kv))
	for k, v := range kv {
		out[k] = V(v)
	}
	return out
}

func upsert(device, entityID string, l int64, kv map[string]any, fv FieldVersions) Change {
	return Change{
		ChangeID: fmt.Sprintf("c-%s-%d", device, l),
		Entity:   "txn",
		EntityID: entityID,
		Op:       OpUpsert,
		Data:     fields(kv),
		FV:       fv,
		Lamport:  l,
		Origin:   device,
	}
}

func del(device, entityID string, l int64) Change {
	return Change{
		ChangeID: fmt.Sprintf("d-%s-%d", device, l),
		Entity:   "txn",
		EntityID: entityID,
		Op:       OpDelete,
		Data:     map[string]json.RawMessage{},
		FV:       FieldVersions{},
		Lamport:  l,
		Origin:   device,
	}
}

// raw returns a field's exact wire bytes.
//
// Asserting on the raw bytes rather than a decoded value is deliberate for a
// ledger: "3200" must stay "3200" forever, and never become 3200.0 or a
// float64 after a restart.
func raw(r *Record, name string) string { return string(r.Values[name]) }

// fingerprint produces a canonical string for a record so that two merge
// orders can be compared for equality.
func fingerprint(r *Record) string {
	if r == nil {
		return "<nil>"
	}
	keys := make([]string, 0, len(r.Values))
	for k := range r.Values {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var b strings.Builder
	fmt.Fprintf(&b, "id=%s del=%v delFV=%d/%s lam=%d\n", r.EntityID, r.Deleted, r.DelFV.L, r.DelFV.D, r.Lamport)
	for _, k := range keys {
		st := r.FV[k]
		fmt.Fprintf(&b, "  %s = %s  [%d/%s]\n", k, r.Values[k], st.L, st.D)
	}
	return b.String()
}

func applyAll(t *testing.T, changes []Change) *Record {
	t.Helper()
	var rec *Record
	for i, ch := range changes {
		if err := ch.Validate(); err != nil {
			t.Fatalf("change #%d is malformed: %v", i, err)
		}
		var res Result
		rec, res = Apply(rec, ch)
		if !res.Changed && i > 0 {
			t.Logf("change #%d applied with no state change", i)
		}
	}
	return rec
}

// ---------------------------------------------------------------------------
// core merge semantics
// ---------------------------------------------------------------------------

func TestFirstSightingCreatesRow(t *testing.T) {
	ch := upsert("phone", "t1", 10, map[string]any{"amount_minor": 3200}, FieldVersions{
		"amount_minor": {L: 10, D: "phone"},
	})
	rec, res := Apply(nil, ch)

	if rec.Deleted {
		t.Fatal("new row must not be tombstoned")
	}
	if got := raw(rec, "amount_minor"); got != "3200" {
		t.Fatalf("amount_minor = %s, want 3200", got)
	}
	if rec.Lamport != 10 {
		t.Fatalf("Lamport = %d, want 10", rec.Lamport)
	}
	if !res.Changed {
		t.Fatal("first sighting must report Changed")
	}
}

// The headline feature: two devices editing DIFFERENT fields of the same
// transaction must not clobber each other. Row-level LWW would lose one.
func TestFieldLevelMergeBothSurvive(t *testing.T) {
	a := upsert("phone", "t1", 20, map[string]any{"amount_minor": 4580}, FieldVersions{
		"amount_minor": {L: 20, D: "phone"},
	})
	b := upsert("tablet", "t1", 21, map[string]any{"note": "和同事AA"}, FieldVersions{
		"note": {L: 21, D: "tablet"},
	})

	rec := applyAll(t, []Change{a, b})

	if got := raw(rec, "amount_minor"); got != "4580" {
		t.Errorf("amount_minor = %s, want 4580 (phone's edit was lost)", got)
	}
	if got := raw(rec, "note"); got != `"和同事AA"` {
		t.Errorf("note = %s, want 和同事AA (tablet's edit was lost)", got)
	}
}

// A stale offline device must not resurrect an old value over a newer one.
func TestStaleEditDoesNotClobber(t *testing.T) {
	cur := applyAll(t, []Change{
		upsert("phone", "t1", 10, map[string]any{"amount_minor": 1000}, FieldVersions{
			"amount_minor": {L: 10, D: "phone"},
		}),
	})
	stale := upsert("tablet", "t1", 5, map[string]any{"amount_minor": 999}, FieldVersions{
		"amount_minor": {L: 5, D: "tablet"},
	})

	rec, res := Apply(cur, stale)

	if got := raw(rec, "amount_minor"); got != "1000" {
		t.Fatalf("amount_minor = %s, want 1000 (stale edit overwrote newer)", got)
	}
	if len(res.RejectedFields) != 1 || res.RejectedFields[0] != "amount_minor" {
		t.Fatalf("RejectedFields = %v, want [amount_minor] — a dropped amount must never be silent", res.RejectedFields)
	}
}

// Lamport ties are broken by device id so the result never depends on
// arrival order. This is what makes the permutation test below pass.
func TestTieBreakIsDeterministicByDevice(t *testing.T) {
	mk := func(device string) Change {
		return upsert(device, "t1", 50, map[string]any{"note": "from-" + device}, FieldVersions{
			"note": {L: 50, D: device},
		})
	}
	alpha, zeta := mk("alpha"), mk("zeta")

	// "zeta" > "alpha" lexicographically, so zeta must win in both orders.
	forward, _ := Apply(applyAll(t, []Change{alpha}), zeta)
	reverse, _ := Apply(applyAll(t, []Change{zeta}), alpha)

	if got := raw(forward, "note"); got != `"from-zeta"` {
		t.Errorf("forward: note = %s, want from-zeta", got)
	}
	if got := raw(reverse, "note"); got != `"from-zeta"` {
		t.Errorf("reverse: note = %s, want from-zeta", got)
	}
}

// Values must survive a JSON round-trip byte-for-byte. Encoding into
// map[string]any would turn 3200 into 3200 and quietly make money a float.
func TestValuesRoundTripWithoutContamination(t *testing.T) {
	ch := upsert("phone", "t1", 10, map[string]any{
		"amount_minor": 9007199254740993, // > 2^53, still exact as int64
		"ratio":        0.1,
		"note":         "含 \" 引号 和 中文",
	}, FieldVersions{
		"amount_minor": {L: 10, D: "phone"},
		"ratio":        {L: 10, D: "phone"},
		"note":         {L: 10, D: "phone"},
	})

	rec := applyAll(t, []Change{ch})
	blob, err := json.Marshal(rec)
	if err != nil {
		t.Fatal(err)
	}
	var back Record
	if err := json.Unmarshal(blob, &back); err != nil {
		t.Fatal(err)
	}

	for _, k := range []string{"amount_minor", "ratio", "note"} {
		if got, want := string(back.Values[k]), string(rec.Values[k]); got != want {
			t.Errorf("%s did not round-trip: %q -> %q", k, want, got)
		}
	}
	if got := raw(&back, "amount_minor"); got != "9007199254740993" {
		t.Errorf("amount_minor after round-trip = %s, want 9007199254740993 (float contamination)", got)
	}
}

// ---------------------------------------------------------------------------
// the convergence property
// ---------------------------------------------------------------------------

// TestConvergenceAcrossPermutations is the test that justifies the whole
// design. A batch of changes that may arrive in any order (offline catch-up,
// retries, concurrent pushes) must converge to a byte-identical result
// regardless of arrival order. If this ever fails, the merge rule is wrong.
func TestConvergenceAcrossPermutations(t *testing.T) {
	changes := []Change{
		upsert("phone", "t1", 10, map[string]any{
			"amount_minor": 3200, "note": "早餐", "category_id": "c_food",
		}, FieldVersions{
			"amount_minor": {L: 10, D: "phone"},
			"note":         {L: 10, D: "phone"},
			"category_id":  {L: 10, D: "phone"},
		}),
		upsert("tablet", "t1", 14, map[string]any{"amount_minor": 2800}, FieldVersions{
			"amount_minor": {L: 14, D: "tablet"},
		}),
		upsert("phone", "t1", 18, map[string]any{"note": "早餐+咖啡"}, FieldVersions{
			"note": {L: 18, D: "phone"},
		}),
		upsert("watch", "t1", 16, map[string]any{"category_id": "c_drink"}, FieldVersions{
			"category_id": {L: 16, D: "watch"},
		}),
		del("phone", "t1", 30),
		upsert("tablet", "t1", 33, map[string]any{"note": "误删"}, FieldVersions{
			"note": {L: 33, D: "tablet"},
		}),
	}

	perms := permutations(len(changes))
	if len(perms) != 720 {
		t.Fatalf("expected 720 permutations, generated %d", len(perms))
	}

	want := ""
	for i, p := range perms {
		ordered := make([]Change, len(changes))
		for j, idx := range p {
			ordered[j] = changes[idx]
		}
		got := fingerprint(applyAll(t, ordered))
		if i == 0 {
			want = got
			continue
		}
		if got != want {
			t.Fatalf("permutation %v diverged\n--- want ---\n%s\n--- got ---\n%s", p, want, got)
		}
	}

	// And pin down what that converged state actually is, so a regression
	// that still converges (but converges to the wrong thing) is caught.
	rec := applyAll(t, changes)
	if rec.Deleted {
		t.Error("lamport 33 edit must resurrect the row deleted at lamport 30")
	}
	if got := raw(rec, "amount_minor"); got != "2800" {
		t.Errorf("amount_minor = %s, want 2800", got)
	}
	if got := raw(rec, "note"); got != `"误删"` {
		t.Errorf("note = %s, want 误删", got)
	}
	if got := raw(rec, "category_id"); got != `"c_drink"` {
		t.Errorf("category_id = %s, want c_drink", got)
	}
}

// ---------------------------------------------------------------------------
// tombstone semantics
// ---------------------------------------------------------------------------

func TestDeleteThenNewerEditResurrects(t *testing.T) {
	cur := applyAll(t, []Change{
		upsert("phone", "t1", 10, map[string]any{"note": "x"}, FieldVersions{"note": {L: 10, D: "phone"}}),
		del("phone", "t1", 20),
	})
	if !cur.Deleted {
		t.Fatal("row should be deleted at lamport 20")
	}

	rec, res := Apply(cur, upsert("phone", "t1", 25, map[string]any{"note": "y"}, FieldVersions{
		"note": {L: 25, D: "phone"},
	}))

	if rec.Deleted {
		t.Fatal("a strictly newer edit must resurrect the row (this is what makes undo work)")
	}
	if !res.Resurrected {
		t.Error("Resurrected flag not set")
	}
}

func TestNewerDeleteBeatsStaleEdit(t *testing.T) {
	cur := applyAll(t, []Change{
		upsert("phone", "t1", 10, map[string]any{"note": "x"}, FieldVersions{"note": {L: 10, D: "phone"}}),
		del("phone", "t1", 40),
	})

	rec, _ := Apply(cur, upsert("tablet", "t1", 12, map[string]any{"note": "stale"}, FieldVersions{
		"note": {L: 12, D: "tablet"},
	}))

	if !rec.Deleted {
		t.Fatal("a delete at lamport 40 must beat a stale edit at lamport 12; the row stays deleted")
	}
	if got := raw(rec, "note"); got != `"stale"` {
		t.Errorf("note = %s, want the stale value preserved under the tombstone", got)
	}
}

func TestDeleteOfAlreadyDeletedIsNoOp(t *testing.T) {
	cur := applyAll(t, []Change{
		upsert("phone", "t1", 10, map[string]any{"note": "x"}, FieldVersions{"note": {L: 10, D: "phone"}}),
		del("phone", "t1", 20),
	})

	rec, res := Apply(cur, del("tablet", "t1", 25))
	if !rec.Deleted {
		t.Fatal("must stay deleted")
	}
	if res.Changed {
		t.Error("re-deleting an already-deleted row should report no change")
	}
}

// ---------------------------------------------------------------------------
// regression guards
// ---------------------------------------------------------------------------

// TestContentNewerThanTombstoneWins is a direct regression guard.
//
// The bug: DelFV was only advanced when a delete won the comparison, so a row
// edited at lamport 33 still carried a tombstone stamp of 16. A delete
// arriving later at lamport 30 then "won" and killed the row — while the same
// changes in a different arrival order resurrected it. Two orders, two
// answers, no error anywhere.
//
// The invariant now is: a row is deleted exactly when the delete stamp strictly
// beats the newest content stamp, regardless of what DelFV happened to be.
func TestContentNewerThanTombstoneWins(t *testing.T) {
	cur := applyAll(t, []Change{
		upsert("watch", "t1", 16, map[string]any{"category_id": "c_drink"}, FieldVersions{
			"category_id": {L: 16, D: "watch"},
		}),
		del("watch", "t1", 16),
		upsert("tablet", "t1", 33, map[string]any{"note": "误删"}, FieldVersions{
			"note": {L: 33, D: "tablet"},
		}),
	})
	if cur.Deleted {
		t.Fatal("content at lamport 33 must beat the tombstone at 16")
	}

	rec, res := Apply(cur, del("phone", "t1", 30))
	if rec.Deleted {
		t.Fatal("a delete at lamport 30 must not kill a row whose content is at 33")
	}
	if !res.DeleteRejected {
		t.Error("DeleteRejected should be reported so the client can tell the user")
	}
}

// A device can create a row and delete it entirely while offline, so the very
// first thing the server ever hears about that row may be a delete.
func TestDeleteArrivingFirstIsRemembered(t *testing.T) {
	rec, res := Apply(nil, del("phone", "t1", 10))

	if !rec.Deleted {
		t.Fatal("a delete for an unknown row must tombstone it")
	}
	if rec.DelFV.L != 10 {
		t.Errorf("DelFV.L = %d, want 10", rec.DelFV.L)
	}
	if !res.Killed {
		t.Error("Killed should be reported")
	}

	rec2, res2 := Apply(rec, upsert("tablet", "t1", 20, map[string]any{"note": "还在"}, FieldVersions{
		"note": {L: 20, D: "tablet"},
	}))
	if rec2.Deleted {
		t.Fatal("a strictly newer edit must resurrect a row deleted offline")
	}
	if !res2.Resurrected {
		t.Error("Resurrected should be reported")
	}
}

// ---------------------------------------------------------------------------
// idempotency
// ---------------------------------------------------------------------------

func TestDuplicateChangeIsIdempotent(t *testing.T) {
	ch := upsert("phone", "t1", 10, map[string]any{"amount_minor": 3200}, FieldVersions{
		"amount_minor": {L: 10, D: "phone"},
	})

	once := fingerprint(applyAll(t, []Change{ch}))
	twice := fingerprint(applyAll(t, []Change{ch, ch, ch}))

	if once != twice {
		t.Fatalf("re-applying the same change mutated state\nonce:\n%s\ntwice:\n%s", once, twice)
	}
}

func TestApplyDoesNotMutateInput(t *testing.T) {
	cur := applyAll(t, []Change{
		upsert("phone", "t1", 10, map[string]any{"note": "x"}, FieldVersions{"note": {L: 10, D: "phone"}}),
	})
	before := fingerprint(cur)

	_, _ = Apply(cur, upsert("tablet", "t1", 99, map[string]any{"note": "y"}, FieldVersions{
		"note": {L: 99, D: "tablet"},
	}))

	if after := fingerprint(cur); after != before {
		t.Fatalf("Apply mutated its input record\nbefore:\n%s\nafter:\n%s", before, after)
	}
}

// ---------------------------------------------------------------------------
// client-compat and validation
// ---------------------------------------------------------------------------

// A client that has not implemented per-field stamps must still sync, at
// row-level granularity, rather than being rejected.
func TestRowLevelFallbackForClientsWithoutFieldStamps(t *testing.T) {
	legacy := upsert("legacy", "t1", 10, map[string]any{"amount_minor": 500}, nil)
	cur, res := Apply(nil, legacy)

	if got := raw(cur, "amount_minor"); got != "500" {
		t.Fatalf("amount_minor = %s, want 500", got)
	}
	if got := cur.FV["amount_minor"]; got.L != 10 || got.D != "legacy" {
		t.Fatalf("FV = %+v, want the row-level stamp used as fallback", got)
	}
	if !res.Changed {
		t.Error("Changed should be set")
	}
}

func TestValidateRejectsMalformed(t *testing.T) {
	cases := []struct {
		name string
		ch   Change
		want ValidationError
	}{
		{"no change id", Change{Entity: "txn", EntityID: "t", Op: OpUpsert, Lamport: 1, Origin: "d"}, ErrMissingChangeID},
		{"no entity", Change{ChangeID: "c", EntityID: "t", Op: OpUpsert, Lamport: 1, Origin: "d"}, ErrMissingEntity},
		{"no entity id", Change{ChangeID: "c", Entity: "txn", Op: OpUpsert, Lamport: 1, Origin: "d"}, ErrMissingEntityID},
		{"no origin", Change{ChangeID: "c", Entity: "txn", EntityID: "t", Op: OpUpsert, Lamport: 1}, ErrMissingOrigin},
		{"bad op", Change{ChangeID: "c", Entity: "txn", EntityID: "t", Op: "nuke", Lamport: 1, Origin: "d"}, ErrBadOp},
		{"zero lamport upsert", Change{ChangeID: "c", Entity: "txn", EntityID: "t", Op: OpUpsert, Origin: "d"}, ErrMissingLamport},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if err := tc.ch.Validate(); err != tc.want {
				t.Fatalf("Validate() = %v, want %v", err, tc.want)
			}
		})
	}
}

// ---------------------------------------------------------------------------
// regression guard on the central invariant
// ---------------------------------------------------------------------------

// TestMergePackageNeverReadsTheClock enforces the property the whole design
// rests on: the merge layer must not import "time". If someone later adds a
// time.Now() here, LWW would silently start depending on device wall clocks
// and reintroduce the exact corruption this package exists to prevent.
//
// Uses go/parser rather than a substring scan, so that this file mentioning
// the word does not trip the check on itself.
func TestMergePackageNeverReadsTheClock(t *testing.T) {
	entries, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) == 0 {
		t.Fatal("no source files found — is the test running in the right directory?")
	}

	for _, e := range entries {
		fset := token.NewFileSet()
		f, err := parser.ParseFile(fset, e, nil, parser.ImportsOnly)
		if err != nil {
			t.Fatalf("parse %s: %v", e, err)
		}
		for _, imp := range f.Imports {
			if p := strings.Trim(imp.Path.Value, `"`); p == "time" {
				t.Errorf("%s imports \"time\"; the merge layer must stay clock-free", e)
			}
		}
	}
}

// ---------------------------------------------------------------------------

// permutations returns every ordering of n distinct items.
//
// Uses the classic swap-in-place algorithm rather than the
// build-a-remaining-slice version: the latter is easy to get subtly wrong,
// and "did I enumerate the right number of orderings" is exactly the kind of
// thing a helper must not be able to silently get wrong. (The first version of
// this helper used make([]int, n) — which produces [0 0], not [0 1] — and
// quietly tested 12 orderings while claiming to test 720.) The caller asserts
// the count, so a broken generator fails loudly.
func permutations(n int) [][]int {
	idx := make([]int, n)
	for i := range idx {
		idx[i] = i
	}

	out := make([][]int, 0, factorial(n))
	var rec func(k int)
	rec = func(k int) {
		if k == n {
			cp := make([]int, n)
			copy(cp, idx)
			out = append(out, cp)
			return
		}
		for i := k; i < n; i++ {
			idx[k], idx[i] = idx[i], idx[k]
			rec(k + 1)
			idx[k], idx[i] = idx[i], idx[k]
		}
	}
	rec(0)

	shuffle(out)
	return out
}

func factorial(n int) int {
	r := 1
	for i := 2; i <= n; i++ {
		r *= i
	}
	return r
}

// shuffle stops the tests from always exercising the same order first, which
// would hide bugs that only show up in a particular sequence.
func shuffle(in [][]int) {
	rng := rand.New(rand.NewSource(20260929))
	rng.Shuffle(len(in), func(i, j int) { in[i], in[j] = in[j], in[i] })
}
