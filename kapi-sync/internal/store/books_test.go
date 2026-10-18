package store_test

import (
	"encoding/json"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"testing"
	"time"
)

func TestLedgerIdentityFilterAndNamesSurviveReplay(t *testing.T) {
	dir := t.TempDir()
	s, err := store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	a := upsert("old", 1, 100, store.DirExpense, "", "A1", "old", "", ts(2026, time.October, 1, 12))
	b := upsert("travel", 2, 900, store.DirExpense, "", "A1", "travel", "", ts(2026, time.October, 1, 12))
	b.Data["ledger_id"] = merge.V("book-trip")
	c := merge.Change{ChangeID: "book", Entity: "ledger", EntityID: "book-trip", Op: merge.OpUpsert, Lamport: 3, Origin: "dev_test", Data: map[string]json.RawMessage{"payload": merge.V(`{"id":"book-trip","name":"旅行账本"}`)}}
	if _, err = s.Push("dev_test", []merge.Change{a, b, c}); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s, err = store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	rows := s.ListTx(store.TxQuery{LedgerID: "book-trip"})
	if rows.Total != 1 || rows.Rows[0].LedgerName != "旅行账本" {
		t.Fatalf("wrong book projection: %+v", rows)
	}
	if s.Stats(store.TxQuery{LedgerID: "L1"}).Expense != 100 {
		t.Fatal("legacy transactions must remain in L1")
	}
	if s.Stats(store.TxQuery{LedgerID: "book-trip"}).Expense != 900 {
		t.Fatal("book summaries must be isolated")
	}
	facets := s.Facets("ledger")
	if len(facets) != 2 {
		t.Fatalf("wrong book facets: %+v", facets)
	}
}
