package family_test

import (
	"encoding/json"
	"kapi/sync/internal/store"
	"testing"
)

func TestFamilyPanelEditOwnRowsOnly(t *testing.T) {
	f := setup(t)
	f.enable()
	a, b := f.join("甲"), f.join("乙")
	ac, bc := a.Shared[0], b.Shared[0]
	f.push(ac, "a-owned", 250)
	var own, other store.TransactionEditor
	f.good("GET", ac.BasePath+"/v1/panel/edit?id=a-owned", ac.Token, nil, &own)
	f.good("GET", bc.BasePath+"/v1/panel/edit?id=a-owned", bc.Token, nil, &other)
	if !own.CanEdit || other.CanEdit {
		t.Fatal("ownership")
	}
	body := map[string]any{"id": "a-owned", "revision": own.Revision, "request_id": "family-edit-000001", "amount": "3.50", "category_id": "", "account_id": "A1", "note": "本人修改"}
	if status, _ := f.call("POST", bc.BasePath+"/v1/panel/edit", bc.Token, body); status != 403 {
		t.Fatal("other member edit", status)
	}
	if status, _ := f.call("POST", ac.BasePath+"/v1/panel/edit", "owner-admin", body); status != 403 {
		t.Fatal("root edited another member", status)
	}
	f.good("POST", ac.BasePath+"/v1/panel/edit", ac.Token, body, nil)
	f.good("POST", ac.BasePath+"/v1/panel/edit", ac.Token, body, nil)
	var row struct {
		Values map[string]json.RawMessage `json:"values"`
	}
	json.Unmarshal(f.row(bc, "a-owned"), &row)
	if string(row.Values["amount_minor"]) != "350" {
		t.Fatal(row)
	}
	if status, _ := f.call("GET", ac.BasePath+"/panel/edit.js", "", nil); status != 200 {
		t.Fatal("scoped script", status)
	}
}
