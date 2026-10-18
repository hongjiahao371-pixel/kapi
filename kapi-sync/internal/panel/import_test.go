package panel

import (
	"bytes"
	"context"
	"encoding/json"
	"kapi/sync/internal/billimport"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func importFixture(t *testing.T) (*store.Store, *Handler, *http.ServeMux) {
	t.Helper()
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	_, err = st.Push("seed", []merge.Change{{ChangeID: "account-seed", Entity: "account", EntityID: "A1", Op: "upsert", Data: map[string]json.RawMessage{"name": merge.V("现金"), "currency": merge.V("CNY"), "kind": merge.V("CASH")}, Lamport: 1, Origin: "seed"}})
	if err != nil {
		t.Fatal(err)
	}
	h, _ := New(st, "import-test")
	mux := http.NewServeMux()
	h.Register(mux)
	return st, h, mux
}
func importPost(mux *http.ServeMux, path string, body []byte, contentType string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", path, bytes.NewReader(body))
	r.Header.Set("X-Panel-Token", "import-test")
	r.Header.Set("Content-Type", contentType)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, r)
	return rr
}
func importUpload(t *testing.T, mux *http.ServeMux, file []byte) importPreview {
	t.Helper()
	var b bytes.Buffer
	w := multipart.NewWriter(&b)
	f, _ := w.CreateFormFile("file", "sample.csv")
	f.Write(file)
	w.WriteField("ledger_id", "L1")
	w.Close()
	rr := importPost(mux, "/v1/panel/import/preview", b.Bytes(), w.FormDataContentType())
	if rr.Code != 200 {
		t.Fatal(rr.Code, rr.Body.String())
	}
	var p importPreview
	if err := json.Unmarshal(rr.Body.Bytes(), &p); err != nil {
		t.Fatal(err)
	}
	return p
}
func TestImportAuthAndOriginGate(t *testing.T) {
	_, _, mux := importFixture(t)
	for _, path := range []string{"/v1/panel/import/preview", "/v1/panel/import/commit"} {
		for _, tc := range []struct {
			header, query, origin string
			status                int
		}{{"", "", "", 401}, {"", "?token=import-test", "", 401}, {"import-test", "", "https://evil.example", 403}} {
			r := httptest.NewRequest("POST", path+tc.query, strings.NewReader("{}"))
			r.Header.Set("X-Panel-Token", tc.header)
			r.Header.Set("Origin", tc.origin)
			rr := httptest.NewRecorder()
			mux.ServeHTTP(rr, r)
			if rr.Code != tc.status {
				t.Fatalf("%s got %d", path, rr.Code)
			}
		}
	}
}
func TestImportCommitCannotAcceptClientSuppliedRows(t *testing.T) {
	st, _, mux := importFixture(t)
	before := st.HealthInfo()
	rr := importPost(mux, "/v1/panel/import/commit", []byte(`{"ticket":"fake","selected":[0],"rows":[{"amountMinor":1}]}`), "application/json")
	if rr.Code != 400 {
		t.Fatal(rr.Code)
	}
	if st.HealthInfo() != before {
		t.Fatal("malformed commit wrote data")
	}
}

func TestInvalidLaterRowRollsBackEarlierCategoryAndBill(t *testing.T) {
	st, _, _ := importFixture(t)
	before := st.HealthInfo()
	seq, _, _ := st.BillImportSnapshot("L1")
	path := "餐饮/新分类"
	r := billimport.Row{OccurredAt: 1791609549000, AmountMinor: 1230, Direction: "EXPENSE", Source: "BILL_ICOST", Category: &path}
	candidates := []billimport.Candidate{{Row: r, Match: "NEW", AccountID: "A1"}, {Row: r, Match: "NEW"}}
	_, err := st.CommitBillImport(seq, "L1", candidates, candidates, store.BillImportCommit{Ticket: "atomic-invalid", Selected: []int{0, 1}, PreserveCategories: true})
	if err == nil || st.HealthInfo() != before {
		t.Fatal("batch validation caused a partial write", err)
	}
}
func TestRealCoreImportPreviewAtomicCommitRepeatAndConcurrentWrite(t *testing.T) {
	jar := os.Getenv("KAPI_IMPORT_JAR")
	if jar == "" {
		t.Skip("Set KAPI_IMPORT_JAR and KAPI_IMPORT_JAVA for the real Android core worker")
	}
	st, h, mux := importFixture(t)
	csv := "日期,类型,金额,一级分类,二级分类,账户1,账户2,备注,货币,标签,账本,位置,退款,优惠,手续费,图片链接1,图片附件1\n2026/10/10 13:19:09,支出,-12.300,餐饮,验收午餐,,,测试,CNY,,测试账本,,,,,,"
	p := importUpload(t, mux, []byte(csv))
	if p.Parsed.SourceName != "iCost" || len(p.Candidates) != 1 || p.Parsed.ExpenseMinor != 1230 {
		t.Fatal("wrong parse", p.Parsed)
	}
	if st.ListTx(store.TxQuery{}).Total != 0 {
		t.Fatal("preview wrote bills")
	}
	options := store.BillImportCommit{Ticket: p.Ticket, Revision: p.Revision, Selected: []int{0}, PreserveCategories: true}
	body, _ := json.Marshal(options)
	rr := importPost(mux, "/v1/panel/import/commit", body, "application/json")
	if rr.Code != 400 || st.ListTx(store.TxQuery{}).Total != 0 {
		t.Fatal("missing account must roll back all")
	}
	options.FallbackAccountID = "A1"
	body, _ = json.Marshal(options)
	rr = importPost(mux, "/v1/panel/import/commit", body, "application/json")
	if rr.Code != 200 {
		t.Fatal(rr.Code, rr.Body.String())
	}
	var saved store.BillImportSaved
	json.Unmarshal(rr.Body.Bytes(), &saved)
	if saved.Added != 1 {
		t.Fatal(saved)
	}
	_, rows, catalog := st.BillImportSnapshot("L1")
	if len(rows) != 1 || len(catalog.Categories) != 2 || rows[0].Row.AmountMinor != 1230 || rows[0].Row.Account == nil || *rows[0].Row.Account != "现金" {
		t.Fatal("values or category hierarchy lost")
	}
	entries, _, _, err := st.Changes(1, 100)
	if err != nil || len(entries) != 3 {
		t.Fatal("category and bill not in changes feed", err, len(entries))
	}
	for _, entry := range entries {
		for _, value := range entry.Snapshot.Values {
			var wire string
			if json.Unmarshal(value, &wire) != nil {
				t.Fatal("new NAS values incompatible with existing Android string-map protocol")
			}
		}
	}
	rr = importPost(mux, "/v1/panel/import/commit", body, "application/json")
	if rr.Code != 200 || st.ListTx(store.TxQuery{}).Total != 1 {
		t.Fatal("lost response retry duplicated")
	}
	p2 := importUpload(t, mux, []byte(csv))
	if p2.Candidates[0].Match != "SUSPECTED" {
		t.Fatal("repeated import not flagged", p2.Candidates)
	}
	// A newly appearing duplicate while the preview is open must not be silently added.
	old := p2.Candidates
	old[0].Match = "NEW"
	seq, _, _ := st.BillImportSnapshot("L1")
	fresh, err := h.imports.worker.Run(context.Background(), billimport.Request{Mode: "match", LedgerID: "L1", Rows: []billimport.Row{p2.Candidates[0].Row}, Existing: rows})
	if err != nil {
		t.Fatal(err)
	}
	fresh.Candidates[0].AccountID = "A1"
	result, err := st.CommitBillImport(seq, "L1", old, fresh.Candidates, store.BillImportCommit{Ticket: "race-preview", Selected: []int{0}, PreserveCategories: true})
	if err != nil || result.Added != 0 || result.Skipped != 1 {
		t.Fatal("concurrent duplicate not skipped", result, err)
	}
	_, err = st.CommitBillImport(seq-1, "L1", old, fresh.Candidates, options)
	if err != store.ErrImportChanged {
		t.Fatal("CAS accepted outdated data", err)
	}
}

func TestDeletedImportRestoresOriginalIdentityAndValues(t *testing.T) {
	st, _, mux := importFixture(t)
	if os.Getenv("KAPI_IMPORT_JAR") == "" {
		t.Skip("real worker required")
	}
	external := "ALIPAY_TXN:202609012200112233445566"
	id := "original-auto-id"
	_, err := st.Push("phone", []merge.Change{{ChangeID: "phone-original", Entity: "txn", EntityID: id, Op: "upsert", Lamport: 2, Origin: "phone", Data: map[string]json.RawMessage{
		"ledger_id": merge.V("L1"), "amount_minor": merge.V(int64(1230)), "direction": merge.V("EXPENSE"), "occurred_at": merge.V(int64(1788249549000)), "account_id": merge.V("A1"), "merchant": merge.V("用户修改商户"), "note": merge.V("保留我的备注"), "shortcut_external_key": merge.V(external)}}})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Push("phone", []merge.Change{{ChangeID: "phone-delete", Entity: "txn", EntityID: id, Op: "delete", Lamport: 3, Origin: "phone"}})
	if err != nil {
		t.Fatal(err)
	}
	_, old, _ := st.BillImportSnapshot("L1")
	csv := "支付宝账单\n交易创建时间,收支,金额,交易对方,收付款方式,交易订单号\n2026-09-01 13:19:09,支出,12.30,导出商户,余额,202609012200112233445566"
	p := importUpload(t, mux, []byte(csv))
	if p.Candidates[0].Match != "DELETED" {
		t.Fatal(p.Candidates)
	}
	if st.ListTx(store.TxQuery{}).Total != 0 {
		t.Fatal("preview resurrected")
	}
	options := store.BillImportCommit{Ticket: p.Ticket, Revision: p.Revision, Selected: []int{0}}
	body, _ := json.Marshal(options)
	rr := importPost(mux, "/v1/panel/import/commit", body, "application/json")
	if rr.Code != 200 {
		t.Fatal(rr.Code, rr.Body.String())
	}
	var saved store.BillImportSaved
	json.Unmarshal(rr.Body.Bytes(), &saved)
	if saved.Restored != 1 || saved.Added != 0 {
		t.Fatal(saved)
	}
	_, next, _ := st.BillImportSnapshot("L1")
	if len(next) != 1 || next[0].Deleted || next[0].ID != id || next[0].Row.AmountMinor != old[0].Row.AmountMinor || *next[0].Row.Note != *old[0].Row.Note || *next[0].Row.Account != *old[0].Row.Account {
		t.Fatal("original identity or edits lost", next)
	}
	retry := importPost(mux, "/v1/panel/import/commit", body, "application/json")
	if retry.Body.String() != rr.Body.String() {
		t.Fatal("retry changed receipt")
	}
	p2 := importUpload(t, mux, []byte(csv))
	if p2.Candidates[0].Match != "DUPLICATE" {
		t.Fatal("restored record not duplicate")
	}
}
func TestDeletedWhilePreviewOpenIsNotImplicitlyRestored(t *testing.T) {
	st, _, _ := importFixture(t)
	r := billimport.Row{OccurredAt: 1791609549000, AmountMinor: 1230, Direction: "EXPENSE", Source: "BILL_ALIPAY"}
	initial := []billimport.Candidate{{Row: r, Match: "NEW"}}
	fresh := []billimport.Candidate{{Row: r, Match: "DELETED", ExistingID: "deleted"}}
	seq, _, _ := st.BillImportSnapshot("L1")
	result, err := st.CommitBillImport(seq, "L1", initial, fresh, store.BillImportCommit{Ticket: "stale", Selected: []int{0}})
	if err != nil || result.Restored != 0 || result.Added != 0 || result.Skipped != 1 {
		t.Fatal(result, err)
	}
}

func TestRealBankPdfPreviewOmissionGateCommitAndRepeat(t *testing.T) {
	path := os.Getenv("KAPI_BANK_PDF")
	if path == "" || os.Getenv("KAPI_IMPORT_JAR") == "" {
		t.Skip("runtime bank PDF fixture required")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	st, _, mux := importFixture(t)
	p := importUpload(t, mux, data)
	if p.Parsed.Source != "BILL_BANK_ICBC" || len(p.Candidates) != 83 || p.Parsed.IssueCount != 45 ||
		p.Parsed.ExpenseMinor != 436911 || p.Parsed.IncomeMinor != 871603 {
		t.Fatal("PDF totals or rows wrong")
	}
	selected := make([]int, 83)
	for i := range selected {
		selected[i] = i
	}
	options := store.BillImportCommit{Ticket: p.Ticket, Revision: p.Revision, Selected: selected, FallbackAccountID: "A1"}
	body, _ := json.Marshal(options)
	rr := importPost(mux, "/v1/panel/import/commit", body, "application/json")
	if rr.Code != 400 || st.ListTx(store.TxQuery{}).Total != 0 {
		t.Fatal("unacknowledged omissions wrote data")
	}
	options.AcknowledgeIssues = true
	body, _ = json.Marshal(options)
	rr = importPost(mux, "/v1/panel/import/commit", body, "application/json")
	if rr.Code != 200 || st.ListTx(store.TxQuery{}).Total != 83 {
		t.Fatal(rr.Code, rr.Body.String())
	}
	repeat := importUpload(t, mux, data)
	for _, c := range repeat.Candidates {
		if c.Match != "DUPLICATE" {
			t.Fatal("repeated bank PDF not duplicate")
		}
	}
}
