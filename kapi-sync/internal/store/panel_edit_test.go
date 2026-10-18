package store_test

import (
	"encoding/json"
	"errors"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"reflect"
	"sync"
	"testing"
)

func editSeed(t *testing.T, dir string) *store.Store {
	t.Helper()
	s, e := store.Open(dir)
	if e != nil {
		t.Fatal(e)
	}
	var cs []merge.Change
	add := func(entity, id string, data map[string]json.RawMessage) {
		cs = append(cs, merge.Change{ChangeID: "seed-" + id, Entity: entity, EntityID: id, Op: merge.OpUpsert, Lamport: 10, Origin: "dev_test", Data: data})
	}
	for _, id := range []string{"A1", "A2", "hidden"} {
		add("account", id, map[string]json.RawMessage{"name": merge.V(id), "currency": merge.V("CNY"), "hidden": merge.V(id == "hidden")})
	}
	for _, id := range []string{"meal", "travel", "income", "foreign"} {
		kind, book := "EXPENSE", "L1"
		if id == "income" {
			kind = "INCOME"
		}
		if id == "foreign" {
			book = "L2"
		}
		add("category", id, map[string]json.RawMessage{"name": merge.V(id), "kind": merge.V(kind), "ledger_id": merge.V(book)})
	}
	r := upsert("original", 20, 10000, "EXPENSE", "meal", "A1", "真实商户", "原备注", 1792100000000)
	r.Data["external_key"] = merge.V("ALIPAY_TXN:receipt-123")
	r.Data["tags"] = merge.V("[\"test\"]")
	r.Data["source"] = merge.V("AUTO")
	cs = append(cs, r)
	if _, e = s.Push("dev_test", cs); e != nil {
		t.Fatal(e)
	}
	return s
}
func editRequest(t *testing.T, s *store.Store) store.TransactionEdit {
	t.Helper()
	v, e := s.TransactionEditor("original", "")
	if e != nil {
		t.Fatal(e)
	}
	return store.TransactionEdit{ID: "original", Revision: v.Revision, RequestID: "request-0000000001", AmountMinor: 12345, AccountID: "A2", CategoryID: "travel", Note: "新备注"}
}
func TestPanelEditPreservesIdentityAndFieldVersionsAcrossRetryAndRestart(t *testing.T) {
	dir := t.TempDir()
	s := editSeed(t, dir)
	r := editRequest(t, s)
	before, _ := s.Row("txn", "original")
	saved, e := s.EditTransaction("", r)
	if e != nil {
		t.Fatal(e)
	}
	cursor := saved.Cursor
	for _, k := range []string{"merchant", "occurred_at", "external_key", "tags", "direction"} {
		if !reflect.DeepEqual(before.Values[k], saved.Record.Values[k]) || before.FV[k] != saved.Record.FV[k] {
			t.Fatal("immutable changed", k)
		}
	}
	for _, k := range []string{"amount_minor", "account_id", "category_id", "note"} {
		if saved.Record.FV[k].L <= before.FV[k].L {
			t.Fatal("missing stamp", k)
		}
	}
	if string(saved.Record.Values["amount_minor"]) != "12345" || string(saved.Record.Values["source"]) != `"MANUAL"` || s.ListTx(store.TxQuery{}).Total != 1 {
		t.Fatal(saved)
	}
	retry, e := s.EditTransaction("", r)
	if e != nil || !retry.Replayed || retry.Cursor != cursor {
		t.Fatal("retry", retry, e)
	}
	r.Note = "different"
	if _, e = s.EditTransaction("", r); !errors.Is(e, store.ErrEditRequestReuse) {
		t.Fatal(e)
	}
	r.Note = "新备注"
	s.Close()
	s, e = store.Open(dir)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	retry, e = s.EditTransaction("", r)
	if e != nil || !retry.Replayed || retry.Cursor != cursor {
		t.Fatal("restart retry", retry, e)
	}
	stale := r
	stale.RequestID = "request-0000000002"
	if _, e = s.EditTransaction("", stale); !errors.Is(e, store.ErrEditConflict) {
		t.Fatal(e)
	}
	editor, _ := s.TransactionEditor(r.ID, "")
	r.Revision = editor.Revision
	r.RequestID = "request-0000000003"
	noop, e := s.EditTransaction("", r)
	if e != nil || !noop.Unchanged || noop.Cursor != cursor {
		t.Fatal("noop", noop, e)
	}
}
func TestPanelEditValidationAndRefundLimits(t *testing.T) {
	s := editSeed(t, t.TempDir())
	defer s.Close()
	base := editRequest(t, s)
	tests := []struct {
		name   string
		change func(*store.TransactionEdit)
	}{{"zero", func(e *store.TransactionEdit) { e.AmountMinor = 0 }}, {"negative", func(e *store.TransactionEdit) { e.AmountMinor = -100 }}, {"too-large", func(e *store.TransactionEdit) { e.AmountMinor = 100_000_000_000 }}, {"missing-account", func(e *store.TransactionEdit) { e.AccountID = "missing" }}, {"hidden-account", func(e *store.TransactionEdit) { e.AccountID = "hidden" }}, {"wrong-kind", func(e *store.TransactionEdit) { e.CategoryID = "income" }}, {"wrong-book", func(e *store.TransactionEdit) { e.CategoryID = "foreign" }}, {"missing-category", func(e *store.TransactionEdit) { e.CategoryID = "unknown" }}}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			r := base
			tc.change(&r)
			if _, e := s.EditTransaction("", r); e == nil {
				t.Fatal("invalid accepted")
			}
		})
	}
	refund := upsert("refund", 30, 4000, "INCOME", "income", "A1", "", "", 1792100000000)
	refund.Data["refund_of_id"] = merge.V("original")
	s.Push("dev_test", []merge.Change{refund})
	base = editRequest(t, s)
	base.AmountMinor = 3999
	if _, e := s.EditTransaction("", base); e == nil {
		t.Fatal("below refunded amount")
	}
	v, _ := s.TransactionEditor("refund", "")
	r := store.TransactionEdit{ID: "refund", Revision: v.Revision, RequestID: "refund-request-0001", AmountMinor: 10001, AccountID: "A1", CategoryID: "income"}
	if _, e := s.EditTransaction("", r); e == nil {
		t.Fatal("over-refund")
	}
	base.AmountMinor = 5000
	base.CategoryID = ""
	if _, e := s.EditTransaction("", base); e != nil {
		t.Fatal(e)
	}
	s.Push("dev_test", []merge.Change{{ChangeID: "delete-original", Entity: "txn", EntityID: "original", Op: merge.OpDelete, Lamport: 1000, Origin: "dev_test"}})
	if _, e := s.EditTransaction("", base); !errors.Is(e, store.ErrEditNotFound) {
		t.Fatal("deleted resurrected", e)
	}
}
func TestPanelEditConcurrentCompareAndSaveAndPhoneFieldMerge(t *testing.T) {
	s := editSeed(t, t.TempDir())
	defer s.Close()
	a := editRequest(t, s)
	b := a
	b.RequestID = "request-0000000002"
	b.Note = "并发修改"
	results := make(chan error, 2)
	var wg sync.WaitGroup
	for _, r := range []store.TransactionEdit{a, b} {
		wg.Add(1)
		go func(e store.TransactionEdit) { defer wg.Done(); _, err := s.EditTransaction("", e); results <- err }(r)
	}
	wg.Wait()
	close(results)
	success, conflict := 0, 0
	for e := range results {
		if e == nil {
			success++
		} else if errors.Is(e, store.ErrEditConflict) {
			conflict++
		} else {
			t.Fatal(e)
		}
	}
	if success != 1 || conflict != 1 {
		t.Fatal(success, conflict)
	}
	_, e := s.Push("phone", []merge.Change{{ChangeID: "phone-queued", Entity: "txn", EntityID: "original", Op: merge.OpUpsert, Lamport: 20, Origin: "phone", Data: map[string]json.RawMessage{"amount_minor": merge.V(10000), "merchant": merge.V("手机补充商户")}}})
	if e != nil {
		t.Fatal(e)
	}
	v, _ := s.Row("txn", "original")
	if string(v.Values["amount_minor"]) != "12345" || string(v.Values["merchant"]) != `"手机补充商户"` || s.ListTx(store.TxQuery{}).Total != 1 {
		t.Fatal(v)
	}
}
