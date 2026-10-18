package api

import (
	"bytes"
	"encoding/json"
	"kapi/sync/internal/capture"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func shortcutFixture(t *testing.T) (*store.Store, *Server, *store.PairResult, string) {
	t.Helper()
	dir := t.TempDir()
	st, e := store.Open(dir)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { st.Close() })
	code, _ := st.PairCode()
	dev, _ := st.Pair(code, "shortcut-test")
	var changes []merge.Change
	add := func(entity, id string, values map[string]json.RawMessage) {
		changes = append(changes, merge.Change{ChangeID: id, Entity: entity, EntityID: id, Op: merge.OpUpsert, Data: values, Lamport: 100 + int64(len(changes)), Origin: dev.DeviceID})
	}
	add("account", "A1", map[string]json.RawMessage{"name": merge.V("现金"), "currency": merge.V("CNY")})
	add("category", "cat0", map[string]json.RawMessage{"name": merge.V("餐饮"), "kind": merge.V("EXPENSE")})
	add("category", "coffee", map[string]json.RawMessage{"name": merge.V("咖啡茶饮"), "kind": merge.V("EXPENSE"), "parent_id": merge.V("cat0")})
	add("ledger", "L1", map[string]json.RawMessage{"payload": merge.V(`{"id":"L1","name":"日常"}`)})
	if _, e = st.Push(dev.DeviceID, changes); e != nil {
		t.Fatal(e)
	}
	return st, New(st), dev, dir
}
func shortcutCall(s *Server, method, path, token string, body any) (int, map[string]any) {
	var b []byte
	if body != nil {
		b, _ = json.Marshal(body)
	}
	req := httptest.NewRequest(method, path, bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer "+token)
	r := httptest.NewRecorder()
	s.ServeHTTP(r, req)
	out := map[string]any{}
	_ = json.Unmarshal(r.Body.Bytes(), &out)
	return r.Code, out
}
func preview(t *testing.T, s *Server, token, id, text string) map[string]any {
	t.Helper()
	code, p := shortcutCall(s, "POST", "/v1/shortcuts/preview", token, map[string]any{"request_id": id, "mode": "capture", "text": text, "captured_at": "2026-10-09T10:00:00+08:00"})
	if code != 200 {
		t.Fatal(code, p)
	}
	return p
}
func commitBody(p map[string]any) map[string]any {
	// Model the user's explicit account selection, independently of a suggestion.
	return map[string]any{"draft_token": p["draft_token"], "amount": "9.90", "direction": "EXPENSE", "merchant": "瑞幸咖啡", "note": p["note"], "category": p["category"], "account": p["accounts"].([]any)[0], "ledger": p["ledger"], "date": p["date_text"], "confirmed": true}
}

func TestShortcutAccountSuggestionDoesNotChooseUnrelatedFirstAccount(t *testing.T) {
	options := []store.ShortcutOption{{ID: "wx", Name: "微信", Label: "微信 · wx"}, {ID: "cash", Name: "现金", Label: "现金 · cash"}, {ID: "ali", Name: "支付宝", Label: "支付宝 · ali"}}
	for _, test := range []struct {
		draft   capture.Draft
		options []store.ShortcutOption
		want    string
	}{
		{capture.Draft{Mode: "capture", Text: "支付宝支付成功", ExternalKey: "ALIPAY_TXN:123456789012"}, options[:2], "请手动选择"},
		{capture.Draft{Mode: "manual"}, options, "请手动选择"},
		{capture.Draft{Mode: "capture", Text: "微信 支付宝"}, options, "请手动选择"},
		{capture.Draft{Mode: "capture", Text: "支付宝 支付成功\n微信推荐", ExternalKey: "ALIPAY_TXN:123456789012"}, options, "支付宝 · ali"},
		{capture.Draft{Mode: "capture", Text: "微信支付成功"}, options, "微信 · wx"},
	} {
		if got := shortcutAccountSuggestion(test.options, test.draft); got != test.want {
			t.Fatalf("%+v got %s", test, got)
		}
	}
	_, server, device, _ := shortcutFixture(t)
	p := preview(t, server, device.Token, "account-suggestion-000001", receipt)
	if p["account"] != "请手动选择" {
		t.Fatal(p["account"])
	}
}

const receipt = "微信支付\n支付成功\n9.90\n商户名称\n瑞幸咖啡\n商品说明\n美式咖啡\n支付时间\n2026-10-09 10:00:00\n交易单号\n4200002026100912345678"

func TestShortcutRoutesRequireDeviceAuthAndCorrectMethods(t *testing.T) {
	_, s, d, _ := shortcutFixture(t)
	for _, p := range []string{"catalog", "preview", "commit"} {
		code, _ := shortcutCall(s, "GET", "/v1/shortcuts/"+p, "bad", nil)
		if code != 401 {
			t.Fatal(p, code)
		}
		method := "GET"
		if p == "catalog" {
			method = "POST"
		}
		code, _ = shortcutCall(s, method, "/v1/shortcuts/"+p, d.Token, nil)
		if code != 405 {
			t.Fatal(p, code)
		}
	}
}
func TestShortcutPreviewIsReadOnlyAndSaveUsesExistingSyncContract(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	before := st.HealthInfo()
	p := preview(t, s, d.Token, "00000000-0000-0000-0000-000000000001", receipt)
	if st.HealthInfo().DBSeq != before.DBSeq {
		t.Fatal("preview wrote data")
	}
	if p["category"] != "餐饮/咖啡茶饮 · coffee" {
		t.Fatal(p["category"])
	}
	code, out := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, commitBody(p))
	if code != 200 || out["status"] != "saved" {
		t.Fatal(code, out)
	}
	row, _ := st.Row("txn", out["id"].(string))
	if string(row.Values["amount_minor"]) != "990" || row.Lamport <= 103 || row.FV["amount_minor"].D != d.DeviceID {
		t.Fatal(row)
	}
	page := st.ListTx(store.TxQuery{})
	if page.Total != 1 || page.Rows[0].CategoryName != "咖啡茶饮" {
		t.Fatal(page)
	}
}
func TestShortcutConcurrentRetryCommitsOnlyOnce(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	p := preview(t, s, d.Token, "00000000-0000-0000-0000-000000000001", receipt)
	b := commitBody(p)
	var wg sync.WaitGroup
	errs := make(chan int, 20)
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			code, _ := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
			errs <- code
		}()
	}
	wg.Wait()
	close(errs)
	for c := range errs {
		if c != 200 {
			t.Fatal(c)
		}
	}
	if st.ListTx(store.TxQuery{}).Total != 1 || st.HealthInfo().DBSeq != 5 {
		t.Fatal(st.HealthInfo())
	}
}
func TestShortcutRetrySurvivesRestartAndDeletionWithoutResurrection(t *testing.T) {
	st, s, d, dir := shortcutFixture(t)
	p := preview(t, s, d.Token, "00000000-0000-0000-0000-000000000001", receipt)
	b := commitBody(p)
	_, first := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
	id := first["id"].(string)
	st.Close()
	st, e := store.Open(dir)
	if e != nil {
		t.Fatal(e)
	}
	defer st.Close()
	s = New(st)
	_, again := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
	if again["status"] != "already_saved" || st.ListTx(store.TxQuery{}).Total != 1 {
		t.Fatal(again)
	}
	st.Push(d.DeviceID, []merge.Change{{ChangeID: "delete", Entity: "txn", EntityID: id, Op: merge.OpDelete, Lamport: 1000, Origin: d.DeviceID}})
	_, again = shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
	if again["status"] != "already_deleted" || st.ListTx(store.TxQuery{}).Total != 0 {
		t.Fatal(again)
	}
}
func TestShortcutReceiptIdentityDeduplicatesDifferentCaptureRequests(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	p := preview(t, s, d.Token, "00000000-0000-0000-0000-000000000001", receipt)
	shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, commitBody(p))
	p = preview(t, s, d.Token, "00000000-0000-0000-0000-000000000002", receipt)
	_, out := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, commitBody(p))
	if out["status"] != "already_saved" || st.ListTx(store.TxQuery{}).Total != 1 {
		t.Fatal(out)
	}
}
func TestShortcutCrossDeviceDraftTamperingAndExpiryRejected(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	p := preview(t, s, d.Token, "00000000-0000-0000-0000-000000000001", receipt)
	b := commitBody(p)
	code, _ := st.PairCode()
	other, _ := st.Pair(code, "other")
	c, _ := shortcutCall(s, "POST", "/v1/shortcuts/commit", other.Token, b)
	if c != 400 {
		t.Fatal(c)
	}
	b["draft_token"] = p["draft_token"].(string) + "tamper"
	c, _ = shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
	if c != 400 {
		t.Fatal(c)
	}
	dev, _ := st.Authenticate(d.Token)
	draft, _ := capture.Parse(receipt, "00000000-0000-0000-0000-000000000001", "capture", time.Now())
	b["draft_token"] = signShortcut(dev, shortcutEnvelope{d.DeviceID, time.Now().Add(-time.Hour).Unix(), draft})
	c, _ = shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
	if c != 400 || st.ListTx(store.TxQuery{}).Total != 0 {
		t.Fatal(c)
	}
}
func TestShortcutConfirmationAmountAndUnsupportedScreensCannotWrite(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	p := preview(t, s, d.Token, "00000000-0000-0000-0000-000000000001", receipt)
	for _, a := range []any{9.9, "1e3", "-10", "0", "1.005"} {
		b := commitBody(p)
		b["amount"] = a
		c, _ := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
		if c != 400 {
			t.Fatal(a, c)
		}
	}
	b := commitBody(p)
	b["confirmed"] = false
	c, _ := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
	if c != 400 {
		t.Fatal(c)
	}
	p = preview(t, s, d.Token, "00000000-0000-0000-0000-000000000002", "退款成功\n9.90")
	c, _ = shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, commitBody(p))
	if c != 400 || st.ListTx(store.TxQuery{}).Total != 0 {
		t.Fatal(c)
	}
}
func TestShortcutSimilarExistingAndroidTxnNeedsExplicitOverride(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	st.Push(d.DeviceID, []merge.Change{{ChangeID: "android", Entity: "txn", EntityID: "existing-android", Op: merge.OpUpsert, Lamport: 105, Origin: d.DeviceID, Data: map[string]json.RawMessage{"amount_minor": merge.V(990), "direction": merge.V("EXPENSE"), "ledger_id": merge.V("L1"), "account_id": merge.V("A1"), "merchant": merge.V("瑞幸咖啡"), "occurred_at": merge.V(time.Date(2026, 10, 9, 10, 0, 0, 0, time.FixedZone("China", 8*3600)).UnixMilli())}}})
	p := preview(t, s, d.Token, "00000000-0000-0000-0000-000000000001", receipt)
	b := commitBody(p)
	_, out := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
	if out["status"] != "needs_review" || st.ListTx(store.TxQuery{}).Total != 1 {
		t.Fatal(out)
	}
	b["allow_possible_duplicate"] = true
	_, out = shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
	if out["status"] != "saved" || st.ListTx(store.TxQuery{}).Total != 2 {
		t.Fatal(out)
	}
}
func TestShortcutDeletedCategoryAndUserRulePriority(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	st.Push(d.DeviceID, []merge.Change{{ChangeID: "rule", Entity: "category_rule", EntityID: "rule", Op: merge.OpUpsert, Lamport: 200, Origin: d.DeviceID, Data: map[string]json.RawMessage{"payload": merge.V(`{"id":"rule","kind":"MERCHANT","pattern":"瑞幸","categoryId":"cat0","ledgerId":"L1","createdAt":1}`)}}})
	p := preview(t, s, d.Token, "00000000-0000-0000-0000-000000000001", receipt)
	if p["category"] != "餐饮 · cat0" {
		t.Fatal(p["category"])
	}
	st.Push(d.DeviceID, []merge.Change{{ChangeID: "cat-delete", Entity: "category", EntityID: "cat0", Op: merge.OpDelete, Lamport: 300, Origin: d.DeviceID}})
	c, out := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, commitBody(p))
	if c != 400 || !strings.Contains(out["message"].(string), "分类") {
		t.Fatal(c, out)
	}
}

func TestShortcutQueuedTaskRetryAfterRePairDoesNotDuplicate(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	id := "00000000-0000-0000-0000-000000000001"
	p := preview(t, s, d.Token, id, receipt)
	shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, commitBody(p))
	code, _ := st.PairCode()
	other, _ := st.Pair(code, "replacement pairing")
	p = preview(t, s, other.Token, id, receipt)
	_, out := shortcutCall(s, "POST", "/v1/shortcuts/commit", other.Token, commitBody(p))
	if out["status"] != "already_saved" || st.ListTx(store.TxQuery{}).Total != 1 {
		t.Fatal(out)
	}
}

func TestShortcutManualPendingValuesAndTextConfirmation(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	code, p := shortcutCall(s, "POST", "/v1/shortcuts/preview", d.Token, map[string]any{"request_id": "manual-20261009-000001", "mode": "manual", "direction": "支出", "manual_amount": "12.30", "manual_merchant": "瑞幸咖啡", "manual_note": "冰美式", "captured_at": "2026-10-09T10:00:00+08:00"})
	if code != 200 || p["amount"] != "12.30" || p["merchant"] != "瑞幸咖啡" || p["note"] != "冰美式" || p["direction_name"] != "支出" || p["can_save"] != "yes" {
		t.Fatal(code, p)
	}
	b := commitBody(p)
	b["amount"] = p["amount"]
	b["confirmed"] = "yes"
	code, out := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
	if code != 200 || out["status"] != "saved" {
		t.Fatal(code, out)
	}
	row, _ := st.Row("txn", out["id"].(string))
	if string(row.Values["amount_minor"]) != "1230" {
		t.Fatal(row)
	}
}
func TestShortcutStrictRequestLimitsAndConfirmation(t *testing.T) {
	st, s, d, _ := shortcutFixture(t)
	for _, body := range []map[string]any{
		{"request_id": "manual-20261009-000001", "mode": "manual", "surprise": true},
		{"request_id": "manual-20261009-000001", "mode": "manual", "manual_amount": "1e3"},
		{"request_id": "manual-20261009-000001", "mode": "manual", "text": strings.Repeat("a", 100<<10)},
	} {
		code, _ := shortcutCall(s, "POST", "/v1/shortcuts/preview", d.Token, body)
		if code != 400 {
			t.Fatal(code)
		}
	}
	p := preview(t, s, d.Token, "manual-20261009-000002", receipt)
	for _, v := range []any{"true", "false", "no", 1, nil} {
		b := commitBody(p)
		b["confirmed"] = v
		code, _ := shortcutCall(s, "POST", "/v1/shortcuts/commit", d.Token, b)
		if code != 400 {
			t.Fatal(v, code)
		}
	}
	if st.ListTx(store.TxQuery{}).Total != 0 {
		t.Fatal("unconfirmed request wrote data")
	}
}
