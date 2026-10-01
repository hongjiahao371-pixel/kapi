package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"kapi/sync/internal/merge"
)

func newTestStore(t *testing.T) (*Store, string) {
	t.Helper()
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s, dir
}

func txn(device, id string, l int64, amount int64) merge.Change {
	return merge.Change{
		ChangeID: device + ":" + id + ":" + itoa(l),
		Entity:   "txn",
		EntityID: id,
		Op:       merge.OpUpsert,
		Data:     map[string]json.RawMessage{"amount_minor": merge.V(amount)},
		FV:       merge.FieldVersions{"amount_minor": {L: l, D: device}},
		Lamport:  l,
		Origin:   device,
	}
}

func itoa(i int64) string {
	if i == 0 {
		return "0"
	}
	var b []byte
	for i > 0 {
		b = append([]byte{byte('0' + i%10)}, b...)
		i /= 10
	}
	return string(b)
}

// ---------------------------------------------------------------------------
// durability
// ---------------------------------------------------------------------------

// The whole promise of this product is "your data does not leave your NAS and
// does not disappear". A restart must be invisible to the user.
func TestRestartReplaysLog(t *testing.T) {
	s, dir := newTestStore(t)

	if _, err := s.Push("dev_a", []merge.Change{
		txn("phone", "t1", 10, 3200),
		txn("phone", "t2", 12, 1500),
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Push("dev_a", []merge.Change{txn("tablet", "t1", 14, 2800)}); err != nil {
		t.Fatal(err)
	}
	before := s.HealthInfo()
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}

	s2, err := Open(dir)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer s2.Close()

	after := s2.HealthInfo()
	if after.DBSeq != before.DBSeq {
		t.Errorf("DBSeq after restart = %d, want %d", after.DBSeq, before.DBSeq)
	}
	if after.Rows != before.Rows {
		t.Errorf("Rows after restart = %d, want %d", after.Rows, before.Rows)
	}

	row, ok := s2.Row("txn", "t1")
	if !ok {
		t.Fatal("t1 missing after restart")
	}
	// Assert on the exact bytes, not a decoded value: a restart must not
	// change 2800 into 2800.0 or a float64.
	if got := string(row.Values["amount_minor"]); got != "2800" {
		t.Errorf("amount_minor after restart = %s, want 2800 (the tablet's correction must survive restart, byte-identical)", got)
	}
}

// A device that times out mid-push and retries must not double-apply. This is
// the single most likely data-corruption path in a home-network app.
func TestDuplicateChangeIsIdempotent(t *testing.T) {
	s, _ := newTestStore(t)
	ch := txn("phone", "t1", 10, 3200)

	r1, err := s.Push("dev_a", []merge.Change{ch})
	if err != nil {
		t.Fatal(err)
	}
	r2, err := s.Push("dev_a", []merge.Change{ch})
	if err != nil {
		t.Fatal(err)
	}
	if r2.Cursor != r1.Cursor {
		t.Errorf("cursor advanced on a duplicate push: %d -> %d", r1.Cursor, r2.Cursor)
	}
	if got := s.HealthInfo().DBSeq; got != 1 {
		t.Errorf("log grew to seq %d on a duplicate push, want 1", got)
	}
	if len(r2.Applied) != 1 {
		t.Errorf("duplicate must be reported as applied, got %v", r2.Applied)
	}
}

// A crash mid-write leaves a partial final line. That change was never
// acknowledged to any client, so dropping it is correct — the client will
// retry. Corruption EARLIER than the final line is a real problem.
func TestTornFinalLineIsDiscarded(t *testing.T) {
	s, dir := newTestStore(t)
	if _, err := s.Push("dev_a", []merge.Change{txn("phone", "t1", 10, 3200)}); err != nil {
		t.Fatal(err)
	}
	s.Close()

	path := filepath.Join(dir, "log.jsonl")
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	f.WriteString(`{"seq":2,"entity":"txn","entity_id":"t2","op":"up`) // torn
	f.Close()

	s2, err := Open(dir)
	if err != nil {
		t.Fatalf("reopen with a torn tail must succeed, got: %v", err)
	}
	defer s2.Close()

	if got := s2.HealthInfo().DBSeq; got != 1 {
		t.Errorf("DBSeq = %d, want 1 (torn tail must be dropped)", got)
	}
	if _, ok := s2.Row("txn", "t2"); ok {
		t.Error("torn record must not be materialised")
	}
	// The log must be physically truncated so the next append is well-formed.
	size, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s2.Push("dev_a", []merge.Change{txn("phone", "t3", 20, 500)}); err != nil {
		t.Fatalf("append after recovery: %v", err)
	}
	newSize, _ := os.Stat(path)
	if newSize.Size() <= size.Size() {
		t.Error("log did not grow after a successful append")
	}
}

func TestCorruptionBeforeFinalLineIsReported(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	s.Close()

	path := filepath.Join(dir, "log.jsonl")
	if err := os.WriteFile(path, []byte("{\"seq\":1,\"entity\":\"txn\"}\nNOT JSON AT ALL\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(dir); err == nil {
		t.Fatal("corruption in the middle of the log must be reported, not silently skipped")
	}
}

// ---------------------------------------------------------------------------
// cursor
// ---------------------------------------------------------------------------

func TestChangesPaginationCoversEverythingExactlyOnce(t *testing.T) {
	s, _ := newTestStore(t)

	const n = 25
	for i := 1; i <= n; i++ {
		if _, err := s.Push("dev_a", []merge.Change{txn("phone", "t"+itoa(int64(i)), int64(10+i), int64(i)*100)}); err != nil {
			t.Fatal(err)
		}
	}

	seen := map[int64]int{}
	cursor := int64(0)
	rounds := 0
	for {
		entries, next, hasMore, err := s.Changes(cursor, 7)
		if err != nil {
			t.Fatal(err)
		}
		for _, e := range entries {
			seen[e.Seq]++
		}
		if next == cursor && len(entries) == 0 {
			break
		}
		cursor = next
		rounds++
		if rounds > 20 {
			t.Fatal("pagination did not terminate")
		}
		if !hasMore {
			break
		}
	}

	if len(seen) != n {
		t.Fatalf("saw %d entries, want %d", len(seen), n)
	}
	for seq, count := range seen {
		if count != 1 {
			t.Errorf("seq %d returned %d times, want exactly 1", seq, count)
		}
	}
}

func TestChangesRejectsBadParams(t *testing.T) {
	s, _ := newTestStore(t)
	if _, _, _, err := s.Changes(-1, 10); err == nil {
		t.Error("negative since must be rejected")
	}
	if _, _, _, err := s.Changes(0, 0); err == nil {
		t.Error("limit 0 must be rejected")
	}
	if _, _, _, err := s.Changes(0, 5000); err == nil {
		t.Error("limit above 1000 must be rejected")
	}
}

// ---------------------------------------------------------------------------
// pairing
// ---------------------------------------------------------------------------

// A paired device must still be paired after the container bounces.
//
// Regression: Device.TokenHash carried `json:"-"`, so the hash was never
// written to meta.json. Every restart rebuilt byHash from empty strings, and
// the first phone to reconnect got "令牌无效" — i.e. a NAS reboot signs your
// devices out of their own data. Nothing errored; the only symptom was a
// user-visible login loop.
func TestPairedDeviceSurvivesRestart(t *testing.T) {
	s, dir := newTestStore(t)

	code, _ := s.PairCode()
	pair, err := s.Pair(code, "Pixel 8")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Authenticate(pair.Token); err != nil {
		t.Fatalf("token must work before restart: %v", err)
	}
	s.Close()

	s2, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer s2.Close()

	d, err := s2.Authenticate(pair.Token)
	if err != nil {
		t.Fatalf("token must still work after restart: %v", err)
	}
	if d.ID != pair.DeviceID {
		t.Errorf("device id after restart = %q, want %q", d.ID, pair.DeviceID)
	}
	if d.Name != "Pixel 8" {
		t.Errorf("device name after restart = %q, want Pixel 8", d.Name)
	}
	if got := s2.HealthInfo().DeviceCount; got != 1 {
		t.Errorf("DeviceCount after restart = %d, want 1", got)
	}
	// And a bogus token must still be rejected after restart.
	if _, err := s2.Authenticate("nope"); err == nil {
		t.Error("a bogus token must be rejected after restart too")
	}
}

func TestPairAndAuthenticate(t *testing.T) {
	s, _ := newTestStore(t)

	code, err := s.PairCode()
	if err != nil {
		t.Fatal(err)
	}
	if len(code) != 6 {
		t.Fatalf("pair code = %q, want 6 digits", code)
	}

	res, err := s.Pair(code, "Pixel 8")
	if err != nil {
		t.Fatal(err)
	}
	if res.Token == "" || res.DeviceID == "" {
		t.Fatal("pair must return a token and device id")
	}

	d, err := s.Authenticate(res.Token)
	if err != nil {
		t.Fatalf("authenticate with the issued token: %v", err)
	}
	if d.Name != "Pixel 8" {
		t.Errorf("device name = %q, want Pixel 8", d.Name)
	}
	if _, err := s.Authenticate("deadbeef"); err == nil {
		t.Error("a bogus token must be rejected")
	}
}

func TestPairCodeExpires(t *testing.T) {
	s, _ := newTestStore(t)

	clock := time.Now()
	s.now = func() time.Time { return clock }

	code, _ := s.PairCode()
	if _, err := s.Pair(code, "phone"); err != nil {
		t.Fatalf("fresh code must work: %v", err)
	}

	clock = clock.Add(PairCodeTTL + time.Minute)
	code2, err := s.PairCode()
	if err != nil {
		t.Fatal(err)
	}
	if code2 == code {
		t.Error("an expired code must be rotated, not reused")
	}
	if _, err := s.Pair(code, "phone"); err == nil {
		t.Error("an expired code must be rejected")
	}
}

// A device id is derived from the token, so two pairings produce different
// identities and revocation is per-device.
func TestEachPairingGetsItsOwnIdentity(t *testing.T) {
	s, _ := newTestStore(t)
	code, _ := s.PairCode()

	a, err := s.Pair(code, "phone")
	if err != nil {
		t.Fatal(err)
	}
	b, err := s.Pair(code, "phone")
	if err != nil {
		t.Fatal(err)
	}
	if a.DeviceID == b.DeviceID {
		t.Error("two pairings must produce distinct device ids")
	}
	if a.Token == b.Token {
		t.Error("two pairings must produce distinct tokens")
	}
	if got := s.HealthInfo().DeviceCount; got != 2 {
		t.Errorf("DeviceCount = %d, want 2", got)
	}
}
