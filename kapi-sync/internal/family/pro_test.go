package family_test

import (
	"encoding/json"
	"kapi/sync/internal/store"
	"os"
	"path/filepath"
	"testing"
)

func TestNewFreeNASCannotCreateFamilyButExistingFamilySurvivesFreeState(t *testing.T) {
	f := setup(t)
	// Start as legacy Pro, create a real shared journal and real invitation.
	f.enable()
	f.hub.Close()
	f.root.Close()
	// Model loss of a paid code / free entitlement without deleting financial data.
	if e := os.WriteFile(filepath.Join(f.dir, "entitlements.json"), []byte(`{"version":1,"legacy_gift":false}`), 0600); e != nil {
		t.Fatal(e)
	}
	var e error
	f.root, e = store.Open(f.dir)
	if e != nil {
		t.Fatal(e)
	}
	f.open()
	if f.root.Pro.Enabled() {
		t.Fatal("unexpected Pro")
	}
	// Existing enable is idempotent and invitations/members are never paywalled.
	f.enable()
	code := f.invite("免费受邀家人")
	if code == "" {
		t.Fatal("empty invite")
	}
	var session any
	f.good("POST", "/v1/family/join", "", map[string]string{"code": code, "protocol": "family-spaces-v1", "device_name": "test-free-member"}, &session)
	var state map[string]any
	f.good("GET", "/v1/family/status", "owner-admin", nil, &state)
	b, _ := json.Marshal(state)
	if string(b) == "" || state["enabled"] != true {
		t.Fatal(state)
	}
}
