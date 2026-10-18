package family_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"kapi/sync/internal/api"
	"kapi/sync/internal/family"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
)

type fixture struct {
	t     *testing.T
	dir   string
	root  *store.Store
	hub   *family.Hub
	mux   *http.ServeMux
	owner *store.PairResult
}

func setup(t *testing.T) *fixture {
	t.Helper()
	dir := t.TempDir()
	root, e := store.Open(dir)
	if e != nil {
		t.Fatal(e)
	}
	code, _ := root.PairCode()
	owner, e := root.Pair(code, "owner phone")
	if e != nil {
		t.Fatal(e)
	}
	f := &fixture{t: t, dir: dir, root: root, owner: owner}
	f.open()
	t.Cleanup(func() { f.hub.Close(); f.root.Close() })
	return f
}
func (f *fixture) open() {
	h, e := family.Open(f.root, f.dir, "owner-admin", func(s *store.Store) http.Handler { return api.New(s) })
	if e != nil {
		f.t.Fatal(e)
	}
	f.hub = h
	f.mux = http.NewServeMux()
	h.Register(f.mux)
}
func (f *fixture) call(method, path, token string, input any) (int, []byte) {
	f.t.Helper()
	var b []byte
	if input != nil {
		b, _ = json.Marshal(input)
	}
	r := httptest.NewRequest(method, path, bytes.NewReader(b))
	if token == "owner-admin" {
		r.Header.Set("X-Panel-Token", token)
	} else if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	f.mux.ServeHTTP(w, r)
	return w.Code, w.Body.Bytes()
}
func (f *fixture) good(method, path, token string, in, out any) {
	f.t.Helper()
	status, b := f.call(method, path, token, in)
	if status != 200 {
		f.t.Fatalf("%s %s: status %d", method, path, status)
	}
	if out != nil {
		if e := json.Unmarshal(b, out); e != nil {
			f.t.Fatal(e)
		}
	}
}
func (f *fixture) enable() { f.good("POST", "/v1/family/enable", "owner-admin", map[string]any{}, nil) }
func (f *fixture) invite(name string) string {
	var v struct {
		Code string `json:"code"`
	}
	f.good("POST", "/v1/family/invite", "owner-admin", map[string]string{"name": name}, &v)
	if len(v.Code) != 24 {
		f.t.Fatal("invalid invite")
	}
	return v.Code
}
func (f *fixture) join(name string) family.Session {
	var s family.Session
	f.good("POST", "/v1/family/join", "", map[string]string{"code": f.invite(name), "device_name": name + " phone", "protocol": "family-spaces-v1"}, &s)
	return s
}
func (f *fixture) push(c family.Connection, id string, amount int64) {
	f.t.Helper()
	change := merge.Change{ChangeID: c.DeviceID + id, Entity: "txn", EntityID: id, Op: merge.OpUpsert, Lamport: 100, Origin: c.DeviceID, Data: map[string]json.RawMessage{"amount_minor": merge.V(amount), "direction": merge.V("EXPENSE"), "ledger_id": merge.V("L1"), "account_id": merge.V("A1"), "occurred_at": merge.V(int64(1792100000000))}}
	f.good("POST", c.BasePath+"/v1/push", c.Token, map[string]any{"device_id": c.DeviceID, "changes": []merge.Change{change}}, nil)
}
func (f *fixture) row(c family.Connection, id string) []byte {
	var b json.RawMessage
	f.good("GET", c.BasePath+"/v1/row?entity=txn&id="+id, c.Token, nil, &b)
	return b
}

func TestMemberPrivateStoresIsolateSameIDsAndSharedRowsConverge(t *testing.T) {
	f := setup(t)
	f.enable()
	a := f.join("甲")
	b := f.join("乙")
	f.push(a.Personal, "same-local-id", 123)
	f.push(b.Personal, "same-local-id", 456)
	if !bytes.Contains(f.row(a.Personal, "same-local-id"), []byte("123")) || !bytes.Contains(f.row(b.Personal, "same-local-id"), []byte("456")) {
		t.Fatal("private values mixed")
	}
	for _, route := range []string{"/v1/changes?since=0", "/v1/row?entity=txn&id=same-local-id", "/v1/backup", "/v1/backups", "/v1/shortcuts/catalog"} {
		status, _ := f.call("GET", b.Personal.BasePath+route, a.Personal.Token, nil)
		if status != 401 {
			t.Fatalf("private route %s leaked (%d)", route, status)
		}
	}
	f.push(a.Shared[0], "shared-one", 250)
	f.row(b.Shared[0], "shared-one")
	// One retry of the same wire change must not append a second transaction.
	f.push(a.Shared[0], "shared-one", 250)
	var p struct {
		Changes []store.LogEntry `json:"changes"`
	}
	f.good("GET", b.Shared[0].BasePath+"/v1/changes?since=0", b.Shared[0].Token, nil, &p)
	n := 0
	for _, e := range p.Changes {
		if e.Entity == "txn" {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("shared retry created %d entries", n)
	}
	if _, ok := f.root.Row("txn", "shared-one"); ok {
		t.Fatal("family bill leaked into legacy personal store")
	}
	if _, ok := f.root.Row("txn", "same-local-id"); ok {
		t.Fatal("member private bill leaked into owner")
	}
}
func TestEnableNeverPublishesOwnerHistory(t *testing.T) {
	f := setup(t)
	_, e := f.root.Push("legacy", []merge.Change{{ChangeID: "old", Entity: "txn", EntityID: "owner-private", Op: merge.OpUpsert, Lamport: 1, Origin: "legacy", Data: map[string]json.RawMessage{"amount_minor": merge.V(100)}}})
	if e != nil {
		t.Fatal(e)
	}
	f.enable()
	a := f.join("甲")
	status, _ := f.call("GET", a.Shared[0].BasePath+"/v1/row?entity=txn&id=owner-private", a.Shared[0].Token, nil)
	if status != 404 {
		t.Fatal("owner history was shared")
	}
	if _, ok := f.root.Row("txn", "owner-private"); !ok {
		t.Fatal("owner history changed")
	}
}
func TestInvitationOneUseProtocolAndOwnership(t *testing.T) {
	f := setup(t)
	f.enable()
	code := f.invite("甲")
	status, _ := f.call("POST", "/v1/family/join", "", map[string]string{"code": code})
	if status != 409 {
		t.Fatal("old client may silently mix spaces")
	}
	var s family.Session
	f.good("POST", "/v1/family/join", "", map[string]string{"code": code, "protocol": "family-spaces-v1"}, &s)
	status, _ = f.call("POST", "/v1/family/join", "", map[string]string{"code": code, "protocol": "family-spaces-v1"})
	if status != 403 {
		t.Fatal("invite was reusable")
	}
	for _, path := range []string{"/v1/family/invite", "/v1/family/enable", "/v1/family/revoke"} {
		status, _ = f.call("POST", path, s.Personal.Token, map[string]string{"name": "other"})
		if status != 403 {
			t.Fatalf("member became owner: %s", path)
		}
	}
	status, _ = f.call("GET", "/v1/family/status", s.Personal.Token, nil)
	if status != 403 {
		t.Fatal("admin registry leaked")
	}
	raw, _ := os.ReadFile(filepath.Join(f.dir, "family", "family.json"))
	if bytes.Contains(raw, []byte(code)) || bytes.Contains(raw, []byte(s.Personal.Token)) || bytes.Contains(raw, []byte(s.Shared[0].Token)) {
		t.Fatal("registry persisted reusable secrets")
	}
}
func TestRevocationBlocksEverySpaceAndSurvivesRestart(t *testing.T) {
	f := setup(t)
	f.enable()
	a := f.join("甲")
	b := f.join("乙")
	f.push(a.Shared[0], "kept-shared", 250)
	f.push(a.Personal, "kept-private", 350)
	f.good("POST", "/v1/family/revoke", "owner-admin", map[string]string{"member_id": a.Member.ID}, nil)
	for _, c := range []family.Connection{a.Personal, a.Shared[0]} {
		for _, path := range []string{"/v1/changes?since=0", "/v1/backup", "/v1/push"} {
			status, _ := f.call("GET", c.BasePath+path, c.Token, nil)
			if status != 403 {
				t.Fatalf("revoked access remains: %s (%d)", path, status)
			}
		}
	}
	f.hub.Close()
	f.open()
	f.row(b.Shared[0], "kept-shared")
	status, _ := f.call("GET", a.Personal.BasePath+"/v1/changes?since=0", a.Personal.Token, nil)
	if status != 403 {
		t.Fatal("restart undid revocation")
	}
	status, _ = f.call("POST", "/v1/family/connections", a.Personal.Token, map[string]string{"protocol": "family-spaces-v1"})
	if status != 403 {
		t.Fatal("revoked member minted new shared credential")
	}
}
func TestPrivateBackupContainsOnlyItsOwnBook(t *testing.T) {
	f := setup(t)
	f.enable()
	a := f.join("甲")
	b := f.join("乙")
	f.push(a.Personal, "only-a-private", 123)
	f.push(b.Personal, "only-b-private", 456)
	f.push(a.Shared[0], "shared", 789)
	status, archive := f.call("GET", a.Personal.BasePath+"/v1/backup", a.Personal.Token, nil)
	if status != 200 {
		t.Fatal(status)
	}
	file := filepath.Join(t.TempDir(), "backup.zip")
	os.WriteFile(file, archive, 0600)
	restored := filepath.Join(t.TempDir(), "restore")
	if e := store.RestoreBackup(file, restored); e != nil {
		t.Fatal(e)
	}
	s, e := store.Open(restored)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if _, ok := s.Row("txn", "only-a-private"); !ok {
		t.Fatal("own backup missing")
	}
	for _, id := range []string{"only-b-private", "shared"} {
		if _, ok := s.Row("txn", id); ok {
			t.Fatal("backup leaked another space")
		}
	}
}
func TestSharedTokensCannotMintMembershipOrAccessOtherSpaces(t *testing.T) {
	f := setup(t)
	f.enable()
	a := f.join("甲")
	for _, path := range []string{"/v1/pair", "/v1/pair/code"} {
		status, _ := f.call("POST", a.Shared[0].BasePath+path, a.Shared[0].Token, map[string]string{"pair_code": "000000"})
		if status != 403 {
			t.Fatal("shared credential minted membership")
		}
	}
	status, _ := f.call("GET", a.Personal.BasePath+"/v1/changes?since=0", a.Shared[0].Token, nil)
	if status != 401 {
		t.Fatal("shared token read private data")
	}
	status, _ = f.call("POST", "/v1/family/connections", a.Shared[0].Token, map[string]string{"protocol": "family-spaces-v1"})
	if status != 403 {
		t.Fatal("shared token became a personal identity")
	}
}
func TestConcurrentPairingAuthenticationAndSharedReads(t *testing.T) {
	f := setup(t)
	f.enable()
	a := f.join("甲")
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 20; j++ {
				f.root.Authenticate(f.owner.Token)
				code, e := f.root.PairCode()
				if e != nil {
					t.Error(e)
					return
				}
				if _, e = f.root.Pair(code, "same member device"); e != nil {
					t.Error(e)
					return
				}
				r := httptest.NewRequest("GET", a.Shared[0].BasePath+"/v1/changes?since=0", nil)
				r.Header.Set("Authorization", "Bearer "+a.Shared[0].Token)
				w := httptest.NewRecorder()
				f.mux.ServeHTTP(w, r)
				if w.Code != 200 {
					t.Error("shared read failed")
				}
			}
		}()
	}
	wg.Wait()
}
func TestRouterRejectsTraversal(t *testing.T) {
	f := setup(t)
	f.enable()
	for _, path := range []string{"/family/space/private/not-an-id/v1/backup", "/family/space/shared/..%2f..%2f/v1/row", "/family/space/private/ffffffffffffffffffffffff/v1/backup"} {
		r := httptest.NewRequest("GET", path, nil)
		r.Header.Set("Authorization", "Bearer "+f.owner.Token)
		w := httptest.NewRecorder()
		f.mux.ServeHTTP(w, r)
		if w.Code == 200 {
			t.Fatal("invalid space path accepted")
		}
	}
}

func TestLegacyDeviceCannotBecomeFamilyAdministrator(t *testing.T) {
	f := setup(t)
	for _, path := range []string{"/v1/family/enable", "/v1/family/invite", "/v1/family/revoke"} {
		code, _ := f.call("POST", path, f.owner.Token, map[string]string{"name": "unauthorized"})
		if code != 403 {
			t.Fatalf("legacy device token became administrator on %s", path)
		}
	}
}

func TestFamilyCreatorPermissionsAreAuthenticatedAndBatchIsAtomic(t *testing.T) {
	f := setup(t)
	f.enable()
	a := f.join("甲")
	b := f.join("乙")
	ac, bc := a.Shared[0], b.Shared[0]
	f.push(ac, "a-owned", 250)
	r := string(f.row(bc, "a-owned"))
	if !bytes.Contains([]byte(r), []byte(a.Member.ID)) {
		t.Fatal("server did not attribute creator")
	}
	makeChange := func(c family.Connection, id string, n int64) merge.Change {
		return merge.Change{ChangeID: c.DeviceID + id + string(rune(n)), Entity: "txn", EntityID: id, Op: merge.OpUpsert, Lamport: n, Origin: "forged-owner", Data: map[string]json.RawMessage{"amount_minor": merge.V(int64(999))}}
	}
	// Even a valid new bill at the start of the same request must not be partly committed.
	status, _ := f.call("POST", bc.BasePath+"/v1/push", bc.Token, map[string]any{"device_id": bc.DeviceID, "changes": []merge.Change{makeChange(bc, "b-new", 200), makeChange(bc, "a-owned", 201)}})
	if status != 403 {
		t.Fatalf("cross-member edit status %d", status)
	}
	status, _ = f.call("GET", bc.BasePath+"/v1/row?entity=txn&id=b-new", bc.Token, nil)
	if status != 404 {
		t.Fatal("forbidden batch partially committed")
	}
	if !bytes.Contains(f.row(ac, "a-owned"), []byte("250")) {
		t.Fatal("forbidden edit changed amount")
	}
	spoof := makeChange(bc, "spoof", 220)
	spoof.Data["family_created_by"] = merge.V("owner")
	status, _ = f.call("POST", bc.BasePath+"/v1/push", bc.Token, map[string]any{"changes": []merge.Change{spoof}})
	if status != 403 {
		t.Fatal("creator spoof accepted")
	}
	spoof = makeChange(bc, "spoof-fv", 221)
	spoof.FV = merge.FieldVersions{"family_created_by": {L: 999, D: "owner"}}
	status, _ = f.call("POST", bc.BasePath+"/v1/push", bc.Token, map[string]any{"changes": []merge.Change{spoof}})
	if status != 403 {
		t.Fatal("creator version spoof accepted")
	}
	edit := makeChange(ac, "a-owned", 300)
	f.good("POST", ac.BasePath+"/v1/push", ac.Token, map[string]any{"changes": []merge.Change{edit}}, nil)
	// The owner's other phone has the same read access, never an edit override.
	var owner struct {
		Shared []family.Connection `json:"shared"`
	}
	f.good("POST", "/v1/family/connections", f.owner.Token, map[string]string{"protocol": "family-spaces-v1"}, &owner)
	oc := owner.Shared[0]
	for _, c := range []family.Connection{bc, oc} {
		for _, op := range []string{merge.OpUpsert, merge.OpDelete} {
			change := makeChange(c, "a-owned", 400)
			change.Op = op
			status, _ := f.call("POST", c.BasePath+"/v1/push", c.Token, map[string]any{"changes": []merge.Change{makeChange(c, "must-not-commit", 399), change}})
			if status != 403 {
				t.Fatalf("foreign %s must fail for device %s: %d", op, c.DeviceID, status)
			}
			status, _ = f.call("GET", c.BasePath+"/v1/row?entity=txn&id=must-not-commit", c.Token, nil)
			if status != 404 {
				t.Fatal("forbidden owner/member batch partly committed")
			}
		}
	}
	if bytes.Contains(f.row(ac, "a-owned"), []byte(`"deleted":true`)) {
		t.Fatal("foreign delete changed bill")
	}
	del := makeChange(ac, "a-owned", 500)
	del.Op = merge.OpDelete
	del.Data = nil
	f.good("POST", ac.BasePath+"/v1/push", ac.Token, map[string]any{"changes": []merge.Change{del}}, nil)
	for _, c := range []family.Connection{bc, oc} {
		status, _ := f.call("POST", c.BasePath+"/v1/push", c.Token, map[string]any{"changes": []merge.Change{makeChange(c, "a-owned", 600)}})
		if status != 403 {
			t.Fatalf("foreign restore accepted: %d", status)
		}
	}
	if !bytes.Contains(f.row(ac, "a-owned"), []byte(`"deleted":true`)) {
		t.Fatal("foreign restore changed tombstone")
	}
	f.good("POST", ac.BasePath+"/v1/push", ac.Token, map[string]any{"changes": []merge.Change{makeChange(ac, "a-owned", 700)}}, nil)
	if bytes.Contains(f.row(ac, "a-owned"), []byte(`"deleted":true`)) {
		t.Fatal("recorder cannot restore own bill")
	}
	if !bytes.Contains(f.row(ac, "a-owned"), []byte(a.Member.ID)) {
		t.Fatal("creator changed")
	}
	// The owner is a regular recorder for bills entered from their own phone.
	f.push(oc, "owner-bill", 123)
	status, _ = f.call("POST", ac.BasePath+"/v1/push", ac.Token, map[string]any{"changes": []merge.Change{makeChange(ac, "owner-bill", 800)}})
	if status != 403 {
		t.Fatal("member edited owner bill")
	}
	for _, op := range []string{merge.OpUpsert, merge.OpDelete, merge.OpUpsert} {
		change := makeChange(oc, "owner-bill", 900)
		change.Op = op
		change.ChangeID += op
		if op == merge.OpDelete {
			change.Lamport = 1000
		} else if bytes.Contains(f.row(oc, "owner-bill"), []byte(`"deleted":true`)) {
			change.Lamport = 1100
			change.ChangeID += "restore"
		}
		f.good("POST", oc.BasePath+"/v1/push", oc.Token, map[string]any{"changes": []merge.Change{change}}, nil)
	}
}

func TestUnpublishedSharedDeletionKeepsCreatorAndCanBeRestored(t *testing.T) {
	f := setup(t)
	f.enable()
	a := f.join("甲")
	c := a.Shared[0]
	del := merge.Change{ChangeID: "first-delete", Entity: "txn", EntityID: "never-pushed", Op: merge.OpDelete, Lamport: 100, Origin: c.DeviceID}
	f.good("POST", c.BasePath+"/v1/push", c.Token, map[string]any{"changes": []merge.Change{del}}, nil)
	row := f.row(c, "never-pushed")
	if !bytes.Contains(row, []byte(`"deleted":true`)) || !bytes.Contains(row, []byte(a.Member.ID)) {
		t.Fatal("tombstone lost deletion or attribution")
	}
	restore := merge.Change{ChangeID: "restore-first-delete", Entity: "txn", EntityID: "never-pushed", Op: merge.OpUpsert, Lamport: 101, Origin: c.DeviceID, Data: map[string]json.RawMessage{"amount_minor": merge.V(int64(250)), "ledger_id": merge.V("L1")}}
	f.good("POST", c.BasePath+"/v1/push", c.Token, map[string]any{"changes": []merge.Change{restore}}, nil)
	if bytes.Contains(f.row(c, "never-pushed"), []byte(`"deleted":true`)) {
		t.Fatal("own unpublished tombstone could not be restored")
	}
}

func TestFamilySpacesHaveIndependentDailyBackups(t *testing.T) {
	f := setup(t)
	f.enable()
	a := f.join("甲")
	f.push(a.Personal, "private", 123)
	f.push(a.Shared[0], "shared", 250)
	if err := f.hub.CreateBackups(); err != nil {
		t.Fatal(err)
	}
	for _, parts := range [][]string{{"family", "private", a.Member.ID, "backups"}, {"family", "shared", a.Shared[0].SpaceID, "backups"}, {"family", "backups"}} {
		entries, err := os.ReadDir(filepath.Join(append([]string{f.dir}, parts...)...))
		if err != nil || len(entries) == 0 {
			t.Fatalf("missing scoped backup: %v", parts)
		}
	}
}

func TestLegacyEditAllCannotGrantCrossRecorderRights(t *testing.T) {
	f := setup(t)
	f.good("POST", "/v1/family/enable", "owner-admin", map[string]any{"allow_member_edits": true}, nil)
	a, b := f.join("甲"), f.join("乙")
	if a.EditAll || b.EditAll {
		t.Fatal("legacy enable granted edit all")
	}
	f.push(a.Shared[0], "owned", 123)
	// A registry written by an older version must not retain the old bypass.
	f.hub.Close()
	path := filepath.Join(f.dir, "family", "family.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var registry map[string]json.RawMessage
	if err = json.Unmarshal(raw, &registry); err != nil {
		t.Fatal(err)
	}
	registry["allow_member_edits"] = json.RawMessage("true")
	raw, err = json.Marshal(registry)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	f.open()
	var status struct {
		EditAll bool `json:"allow_member_edits"`
	}
	f.good("GET", "/v1/family/status", "owner-admin", nil, &status)
	if status.EditAll {
		t.Fatal("legacy registry advertised edit all")
	}
	change := merge.Change{ChangeID: "legacy-attempt", Entity: "txn", EntityID: "owned", Op: merge.OpUpsert, Lamport: 200, Origin: b.Shared[0].DeviceID, Data: map[string]json.RawMessage{"amount_minor": merge.V(999)}}
	code, _ := f.call("POST", b.Shared[0].BasePath+"/v1/push", b.Shared[0].Token, map[string]any{"changes": []merge.Change{change}})
	if code != 403 {
		t.Fatalf("legacy registry bypass: %d", code)
	}
}

func TestUnattributedBillsFailClosedForOwnerAndMembers(t *testing.T) {
	f := setup(t)
	f.enable()
	a := f.join("甲")
	f.hub.Close()
	shared, err := store.Open(filepath.Join(f.dir, "family", "shared", a.Shared[0].SpaceID))
	if err != nil {
		t.Fatal(err)
	}
	_, err = shared.Push("historical", []merge.Change{{ChangeID: "unattributed", Entity: "txn", EntityID: "legacy-unknown", Op: merge.OpUpsert, Lamport: 100, Origin: "historical", Data: map[string]json.RawMessage{"amount_minor": merge.V(123)}}})
	if closeErr := shared.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		t.Fatal(err)
	}
	f.open()
	var owner struct {
		Shared []family.Connection `json:"shared"`
	}
	f.good("POST", "/v1/family/connections", f.owner.Token, map[string]string{"protocol": "family-spaces-v1"}, &owner)
	for _, c := range []family.Connection{a.Shared[0], owner.Shared[0]} {
		for _, op := range []string{merge.OpUpsert, merge.OpDelete} {
			ch := merge.Change{ChangeID: c.DeviceID + op, Entity: "txn", EntityID: "legacy-unknown", Op: op, Lamport: 200, Origin: c.DeviceID, Data: map[string]json.RawMessage{"amount_minor": merge.V(999)}}
			code, _ := f.call("POST", c.BasePath+"/v1/push", c.Token, map[string]any{"changes": []merge.Change{ch}})
			if code != 403 {
				t.Fatalf("unattributed %s accepted: %d", op, code)
			}
		}
	}
	if bytes.Contains(f.row(a.Shared[0], "legacy-unknown"), []byte(`"deleted":true`)) {
		t.Fatal("unattributed bill deleted")
	}
}
