package panel

import (
	"encoding/json"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
)

func reviewSeed(t *testing.T, st *store.Store) {
	t.Helper()
	_, err := st.Push("seed", []merge.Change{
		{ChangeID: "cat-meal", Entity: "category", EntityID: "MEAL", Op: "upsert", Lamport: 3, Origin: "seed", Data: map[string]json.RawMessage{"ledger_id": merge.V("L1"), "name": merge.V("正餐"), "kind": merge.V("EXPENSE")}},
		{ChangeID: "cat-income", Entity: "category", EntityID: "INCOME", Op: "upsert", Lamport: 4, Origin: "seed", Data: map[string]json.RawMessage{"ledger_id": merge.V("L1"), "name": merge.V("工资"), "kind": merge.V("INCOME")}},
		{ChangeID: "cat-foreign", Entity: "category", EntityID: "FOREIGN", Op: "upsert", Lamport: 5, Origin: "seed", Data: map[string]json.RawMessage{"ledger_id": merge.V("other"), "name": merge.V("正餐"), "kind": merge.V("EXPENSE")}},
	})
	if err != nil {
		t.Fatal(err)
	}
}
func reviewPost(mux *http.ServeMux, path string, body any) *httptest.ResponseRecorder {
	data, _ := json.Marshal(body)
	return importPost(mux, path, data, "application/json")
}
func TestImportRepairMappingStrictValidationRevisionAndRememberAfterActualWrite(t *testing.T) {
	if os.Getenv("KAPI_IMPORT_JAR") == "" {
		t.Skip("real core required")
	}
	st, _, mux := importFixture(t)
	reviewSeed(t, st)
	csv := "支付宝账单\n交易时间,交易分类,收/支,金额,交易订单号\n错误时间,测试原分类,支出,1.29,123456789012\n2026-10-11 12:00,测试原分类,支出,1.8,123456789013"
	p := importUpload(t, mux, []byte(csv))
	before := st.HealthInfo()
	if p.Parsed.SourceRecords != 2 || p.Parsed.IssueCount != 1 || len(p.Candidates) != 1 {
		t.Fatal(p)
	}
	fields := reviewPost(mux, "/v1/panel/import/row", map[string]any{"ticket": p.Ticket, "revision": p.Revision, "row": 1})
	if fields.Code != 200 {
		t.Fatal(fields.Body.String())
	}
	var data struct{ Values map[string]string }
	json.Unmarshal(fields.Body.Bytes(), &data)
	if data.Values["TIME"] != "错误时间" || data.Values["ID"] != "" {
		t.Fatal(data)
	}
	forbidden := reviewPost(mux, "/v1/panel/import/repair", map[string]any{"ticket": p.Ticket, "revision": p.Revision, "row": 1, "changes": map[string]string{"ID": "OTHER"}})
	if forbidden.Code != 400 || st.HealthInfo() != before {
		t.Fatal("forbidden identity edit")
	}
	fixed := reviewPost(mux, "/v1/panel/import/repair", map[string]any{"ticket": p.Ticket, "revision": p.Revision, "row": 1, "changes": map[string]string{"TIME": "2026-10-10 12:00"}})
	if fixed.Code != 200 {
		t.Fatal(fixed.Body.String())
	}
	var p2 importPreview
	json.Unmarshal(fixed.Body.Bytes(), &p2)
	if p2.Revision != p.Revision+1 || len(p2.Candidates) != 2 || p2.Parsed.IssueCount != 0 || p2.Parsed.ExpenseMinor != 309 || st.HealthInfo() != before {
		t.Fatal("repair wrote data or did not revalidate")
	}
	options := store.BillImportCommit{Ticket: p2.Ticket, Revision: p.Revision, Selected: []int{0, 1}, FallbackAccountID: "A1", RememberMappings: true, Choices: map[int]store.BillImportChoice{0: {CategoryID: "MEAL"}, 1: {CategoryID: "MEAL"}}}
	if reviewPost(mux, "/v1/panel/import/commit", options).Code != 409 {
		t.Fatal("stale selection accepted")
	}
	options.Revision = p2.Revision
	for _, id := range []string{"INCOME", "FOREIGN", "MISSING"} {
		options.Choices[1] = store.BillImportChoice{CategoryID: id}
		r := reviewPost(mux, "/v1/panel/import/commit", options)
		if r.Code != 400 || st.HealthInfo() != before || len(st.ImportCategoryMappings("L1")) != 0 {
			t.Fatal("invalid category partly wrote", id, r.Body.String())
		}
	}
	options.Choices[1] = store.BillImportChoice{CategoryID: "MEAL"}
	result := reviewPost(mux, "/v1/panel/import/commit", options)
	if result.Code != 200 {
		t.Fatal(result.Body.String())
	}
	var saved store.BillImportSaved
	json.Unmarshal(result.Body.Bytes(), &saved)
	if saved.Added != 2 || saved.ExpenseMinor != 309 || saved.Remembered != 1 || len(st.ImportCategoryMappings("other")) != 0 {
		t.Fatal(saved)
	}
	_, rows, _ := st.BillImportSnapshot("L1")
	if len(rows) != 2 || *rows[0].Row.Category != "正餐" || *rows[1].Row.Category != "正餐" {
		t.Fatal(rows)
	}
	if reviewPost(mux, "/v1/panel/import/commit", options).Body.String() != result.Body.String() {
		t.Fatal("submit retry changed result")
	}
	next := importUpload(t, mux, []byte("支付宝账单\n交易时间,交易分类,收/支,金额,交易订单号\n2026-10-12,测试原分类,支出,2.5,123456789014"))
	if next.Candidates[0].CategoryID != "MEAL" {
		t.Fatal("remembered mapping not applied", next)
	}
	repeated := importUpload(t, mux, []byte("支付宝账单\n交易时间,交易分类,收/支,金额,交易订单号\n2026-10-10 12:00,测试原分类,支出,1.29,123456789012\n2026-10-11 12:00,测试原分类,支出,1.8,123456789013"))
	for _, c := range repeated.Candidates {
		if c.Match != "DUPLICATE" || c.ExistingRow == nil {
			t.Fatal("missing duplicate comparison", c)
		}
	}
}
func TestRepairAuthenticatesAndCannotReadValidRow(t *testing.T) {
	_, _, mux := importFixture(t)
	for _, path := range []string{"/v1/panel/import/row", "/v1/panel/import/repair"} {
		r := httptest.NewRequest("POST", path, nil)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, r)
		if w.Code != 401 {
			t.Fatal(path, w.Code)
		}
	}
	if os.Getenv("KAPI_IMPORT_JAR") == "" {
		return
	}
	p := importUpload(t, mux, []byte("支付宝账单\n交易时间,收/支,金额,交易订单号\n2026-10-10,支出,1.29,123456789012"))
	if reviewPost(mux, "/v1/panel/import/row", map[string]any{"ticket": p.Ticket, "revision": p.Revision, "row": 1}).Code != 400 {
		t.Fatal("valid row exposed as repairable")
	}
}

func TestManualCategoryOptOutAndDuplicatesNeverLearn(t *testing.T) {
	if os.Getenv("KAPI_IMPORT_JAR") == "" {
		t.Skip("real core required")
	}
	st, _, mux := importFixture(t)
	reviewSeed(t, st)
	csv := []byte("支付宝账单\n交易时间,交易分类,收/支,金额,交易订单号\n2026-10-10,测试原分类,支出,1.29,123456789012")
	p := importUpload(t, mux, csv)
	o := store.BillImportCommit{Ticket: p.Ticket, Revision: p.Revision, Selected: []int{0}, FallbackAccountID: "A1", Choices: map[int]store.BillImportChoice{0: {CategoryID: "MEAL"}}}
	if reviewPost(mux, "/v1/panel/import/commit", o).Code != 200 || len(st.ImportCategoryMappings("L1")) != 0 {
		t.Fatal("opt out learned a mapping")
	}
	p = importUpload(t, mux, csv)
	o.Ticket = p.Ticket
	o.Revision = p.Revision
	o.RememberMappings = true
	r := reviewPost(mux, "/v1/panel/import/commit", o)
	if r.Code != 200 || len(st.ImportCategoryMappings("L1")) != 0 {
		t.Fatal("duplicate-only submission learned a mapping", r.Body.String())
	}
}
