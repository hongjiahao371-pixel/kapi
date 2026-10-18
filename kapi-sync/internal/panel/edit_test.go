package panel

import (
	"bytes"
	"encoding/json"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPanelEditHTTPValidationRetryAndConflict(t *testing.T) {
	s, e := store.Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	s.Push("seed", []merge.Change{{ChangeID: "seed", Entity: "txn", EntityID: "receipt", Op: merge.OpUpsert, Lamport: 1, Origin: "seed", Data: map[string]json.RawMessage{"amount_minor": merge.V(1000), "direction": merge.V("EXPENSE"), "account_id": merge.V("A1")}}})
	h, _ := New(s, "edit-test-token")
	mux := http.NewServeMux()
	h.Register(mux)
	call := func(method, path, token, origin string, body any) *httptest.ResponseRecorder {
		b, _ := json.Marshal(body)
		r := httptest.NewRequest(method, path, bytes.NewReader(b))
		r.Header.Set("X-Panel-Token", token)
		r.Header.Set("Origin", origin)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, r)
		return w
	}
	if call("GET", "/v1/panel/edit?id=receipt", "", "", nil).Code != 401 {
		t.Fatal("unauthed")
	}
	w := call("GET", "/v1/panel/edit?id=receipt", "edit-test-token", "", nil)
	var v store.TransactionEditor
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &v) != nil || !v.CanEdit {
		t.Fatal(w.Code, w.Body)
	}
	body := map[string]any{"id": "receipt", "revision": v.Revision, "request_id": "request-0000000001", "amount": "12.34", "account_id": "A1", "category_id": "", "note": "桌面修正"}
	if call("POST", "/v1/panel/edit?token=edit-test-token", "", "", body).Code != 401 {
		t.Fatal("query-token write")
	}
	if call("POST", "/v1/panel/edit", "edit-test-token", "https://unrelated.example", body).Code != 403 {
		t.Fatal("cross-origin")
	}
	for _, amount := range []string{"0", "-1", "NaN", "1e3", "1.001", "9999999999", "1,000", "０.１"} {
		body["amount"] = amount
		w = call("POST", "/v1/panel/edit", "edit-test-token", "", body)
		if w.Code != 400 {
			t.Fatal(amount, w.Code, w.Body)
		}
	}
	body["amount"] = "12.34"
	body["direction"] = "INCOME"
	if call("POST", "/v1/panel/edit", "edit-test-token", "", body).Code != 400 {
		t.Fatal("unexpected field accepted")
	}
	delete(body, "direction")
	w = call("POST", "/v1/panel/edit", "edit-test-token", "http://example.com", body)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body)
	}
	w = call("POST", "/v1/panel/edit", "edit-test-token", "", body)
	if w.Code != 200 || !bytes.Contains(w.Body.Bytes(), []byte(`"replayed":true`)) {
		t.Fatal(w.Code, w.Body)
	}
	body["request_id"] = "request-0000000002"
	if call("POST", "/v1/panel/edit", "edit-test-token", "", body).Code != 409 {
		t.Fatal("stale overwritten")
	}
	if s.ListTx(store.TxQuery{}).Total != 1 {
		t.Fatal("duplicate row")
	}
	if call("GET", "/panel/edit.js", "", "", nil).Code != 200 {
		t.Fatal("script missing")
	}
}
