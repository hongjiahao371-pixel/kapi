package panel

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
)

func TestIncomeCategoryHTTPFiltersStatsAndTransactions(t *testing.T) {
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	var changes []merge.Change
	for i, dir := range []string{"INCOME", "EXPENSE"} {
		changes = append(changes, merge.Change{ChangeID: dir, Entity: "txn", EntityID: dir, Op: merge.OpUpsert, Lamport: int64(i + 1), Origin: "test", Data: map[string]json.RawMessage{
			"amount_minor": merge.V(10000), "direction": merge.V(dir), "category_id": merge.V("工资"), "ledger_id": merge.V("L1"), "occurred_at": merge.V(1790000000000),
		}})
	}
	if _, err := st.Push("test", changes); err != nil {
		t.Fatal(err)
	}
	h, _ := New(st, "income-panel-token")
	mux := http.NewServeMux()
	h.Register(mux)
	for _, path := range []string{"/v1/panel/summary?income_category=工资&ledger_id=L1", "/v1/panel/tx?income_category=工资&ledger_id=L1"} {
		req := httptest.NewRequest("GET", path, nil)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		if w.Code != 401 {
			t.Fatal("income endpoint lost authentication", w.Code)
		}
		req.Header.Set("X-Panel-Token", "income-panel-token")
		w = httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
		if path[:17] == "/v1/panel/summary" {
			var out struct {
				Stats store.Stats `json:"stats"`
			}
			if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
				t.Fatal(err)
			}
			if out.Stats.Count != 1 || out.Stats.Income != 10000 || len(out.Stats.ByIncomeCategory) != 1 {
				t.Fatal(w.Body.String())
			}
		} else {
			var out store.TxPage
			if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
				t.Fatal(err)
			}
			if out.Total != 1 || out.Rows[0].ID != "INCOME" {
				t.Fatal(w.Body.String())
			}
		}
	}
}
