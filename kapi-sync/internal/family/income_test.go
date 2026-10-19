package family_test

import (
	"encoding/json"
	"testing"

	"kapi/sync/internal/family"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
)

func TestIncomeDrillPreservesPersonalAndSharedSpaceIsolation(t *testing.T) {
	f := setup(t)
	f.enable()
	a, b := f.join("甲"), f.join("乙")
	push := func(c family.Connection, id string, n int64) {
		change := merge.Change{ChangeID: c.DeviceID + id, Entity: "txn", EntityID: id, Op: merge.OpUpsert, Lamport: 1, Origin: c.DeviceID, Data: map[string]json.RawMessage{"amount_minor": merge.V(n), "direction": merge.V("INCOME"), "category_id": merge.V("工资"), "ledger_id": merge.V("L1"), "occurred_at": merge.V(int64(1790000000000))}}
		f.good("POST", c.BasePath+"/v1/push", c.Token, map[string]any{"device_id": c.DeviceID, "changes": []merge.Change{change}}, nil)
	}
	push(a.Personal, "same-income", 10000)
	push(b.Personal, "same-income", 90000)
	push(a.Shared[0], "shared-a", 3000)
	push(b.Shared[0], "shared-b", 4000)
	for _, tc := range []struct {
		c      family.Connection
		amount int64
		count  int
	}{{a.Personal, 10000, 1}, {b.Personal, 90000, 1}, {a.Shared[0], 7000, 2}, {b.Shared[0], 7000, 2}} {
		var summary struct {
			Stats store.Stats `json:"stats"`
		}
		f.good("GET", tc.c.BasePath+"/v1/panel/summary?income_category=工资", tc.c.Token, nil, &summary)
		var page store.TxPage
		f.good("GET", tc.c.BasePath+"/v1/panel/tx?income_category=工资", tc.c.Token, nil, &page)
		if summary.Stats.Income != tc.amount || summary.Stats.Count != tc.count || page.Total != tc.count || len(summary.Stats.ByIncomeCategory) != 1 {
			t.Fatalf("space income mixed: %+v %+v", summary, page)
		}
	}
	for _, path := range []string{"/v1/panel/summary?income_category=工资", "/v1/panel/tx?income_category=工资"} {
		if status, _ := f.call("GET", b.Personal.BasePath+path, a.Personal.Token, nil); status != 401 {
			t.Fatalf("private income leaked: %d", status)
		}
	}
}
