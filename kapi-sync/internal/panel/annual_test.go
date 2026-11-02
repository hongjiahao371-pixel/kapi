package panel

import (
	"bytes"
	"encoding/json"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/pro"
	"kapi/sync/internal/store"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestAnnualReportGatesServerAndKeepsFreeStatisticsExportAndData(t *testing.T) {
	st, e := store.Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	defer st.Close()
	h, _ := New(st, "annual-token")
	mux := http.NewServeMux()
	h.Register(mux)
	request := func(path, token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("GET", path, nil)
		r.Header.Set("X-Panel-Token", token)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, r)
		return w
	}
	if request("/v1/panel/reports/annual?year=2026", "").Code != 401 || request("/v1/panel/reports/annual?year=2026", "annual-token").Code != 403 {
		t.Fatal("annual gate missing")
	}
	if request("/v1/panel/summary", "annual-token").Code != 200 || request("/v1/panel/tx", "annual-token").Code != 200 {
		t.Fatal("basic statistics/full record access were paywalled")
	}
	if _, e = pro.Open(t.TempDir(), true); e != nil {
		t.Fatal(e)
	}
	st.Pro, e = pro.Open(t.TempDir(), true)
	if e != nil {
		t.Fatal(e)
	}
	at := time.Date(2026, 1, 15, 12, 0, 0, 0, time.Local).UnixMilli()
	row := func(id, ledger, direction string, amount int64, extra map[string]json.RawMessage) merge.Change {
		data := map[string]json.RawMessage{"ledger_id": merge.V(ledger), "direction": merge.V(direction), "amount_minor": merge.V(amount), "occurred_at": merge.V(at)}
		for k, v := range extra {
			data[k] = v
		}
		return merge.Change{ChangeID: id, Entity: "txn", EntityID: id, Op: merge.OpUpsert, Lamport: 1, Origin: "fixture", Data: data}
	}
	changes := []merge.Change{row("salary", "L1", "INCOME", 10000, nil), row("meal", "L1", "EXPENSE", 3000, nil), row("refund", "L1", "INCOME", 500, map[string]json.RawMessage{"refund_of_id": merge.V("meal")}), row("transfer", "L1", "EXPENSE", 4000, map[string]json.RawMessage{"to_account_id": merge.V("A2")}), row("other", "L2", "INCOME", 99000, nil)}
	if _, e = st.Push("fixture", changes); e != nil {
		t.Fatal(e)
	}
	before := request("/v1/panel/tx?ledger_id=L1", "annual-token").Body.Bytes()
	w := request("/v1/panel/reports/annual?year=2026&ledger_id=L1", "annual-token")
	var report struct {
		Months                   []store.Stats `json:"months"`
		Income, Expense, Balance int64
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &report) != nil || len(report.Months) != 12 || report.Income != 10000 || report.Expense != 2500 || report.Balance != 7500 {
		t.Fatal(w.Code, w.Body.String())
	}
	after := request("/v1/panel/tx?ledger_id=L1", "annual-token").Body.Bytes()
	if !bytes.Equal(before, after) {
		t.Fatal("reading report changed financial records")
	}
	if request("/v1/panel/reports/annual?year=9999", "annual-token").Code != 400 {
		t.Fatal("invalid year accepted")
	}
}
