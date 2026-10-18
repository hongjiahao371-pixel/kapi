package store_test

import (
	"encoding/json"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"testing"
	"time"
)

func TestCategoryDrillMatchesNetExpenseRefundsAndExistingScope(t *testing.T) {
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	var changes []merge.Change
	for i, id := range []string{"cat-a", "cat-b"} {
		c := upsert(id, int64(i+1), 0, "", "", "", "", "", 0)
		c.Entity = "category"
		c.Data = map[string]json.RawMessage{"name": merge.V("正餐")}
		changes = append(changes, c)
	}
	makeRow := func(id string, n int64, dir, cat, book string, at int64) merge.Change {
		c := upsert(id, int64(len(changes)+10), n, dir, cat, "bank", "测试商户", "", at)
		c.Data["ledger_id"] = merge.V(book)
		return c
	}
	add := func(c merge.Change) { changes = append(changes, c) }
	add(makeRow("meal", 10000, "EXPENSE", "cat-a", "L1", ts(2026, time.September, 2, 12)))
	add(makeRow("snack", 2000, "EXPENSE", "cat-b", "L1", ts(2026, time.September, 3, 12)))
	add(makeRow("other-book", 9999, "EXPENSE", "cat-a", "L2", ts(2026, time.September, 3, 12)))
	refund := makeRow("refund", 3000, "INCOME", "different-category", "L1", ts(2026, time.September, 4, 12))
	refund.Data["refund_of_id"] = merge.V("meal")
	add(refund)
	add(makeRow("old-meal", 5000, "EXPENSE", "cat-a", "L1", ts(2026, time.August, 31, 12)))
	refund = makeRow("late-refund", 1000, "INCOME", "", "L1", ts(2026, time.September, 5, 12))
	refund.Data["refund_of_id"] = merge.V("old-meal")
	add(refund)
	add(makeRow("income", 7000, "INCOME", "cat-a", "L1", ts(2026, time.September, 6, 12)))
	transfer := makeRow("transfer", 1000, "EXPENSE", "cat-a", "L1", ts(2026, time.September, 6, 12))
	transfer.Data["to_account_id"] = merge.V("another")
	add(transfer)
	add(makeRow("unclassified", 500, "EXPENSE", "", "L1", ts(2026, time.September, 7, 12)))
	add(makeRow("deleted", 8000, "EXPENSE", "cat-a", "L1", ts(2026, time.September, 8, 12)))
	changes = append(changes, merge.Change{ChangeID: "delete-meal", Entity: "txn", EntityID: "deleted", Op: merge.OpDelete, Lamport: 100, Origin: "dev_test"})
	if _, err := st.Push("dev_test", changes); err != nil {
		t.Fatal(err)
	}
	q := store.TxQuery{LedgerID: "L1", From: ts(2026, time.September, 1, 0), To: ts(2026, time.October, 1, 0), ExpenseCategory: "正餐", IncludeDeleted: true}
	page := st.ListTx(q)
	if page.Total != 4 {
		t.Fatalf("expected two purchases and two refunds, got %+v", page)
	}
	var net int64
	for _, row := range page.Rows {
		net -= row.SignedMinor()
	}
	stats := st.Stats(q)
	if net != 8000 || stats.Expense != net || stats.Count != 4 {
		t.Fatalf("list/stats mismatch: %d %+v", net, stats)
	}
	q.Limit = 1
	q.Offset = 1
	paged := st.ListTx(q)
	if paged.Total != 4 || len(paged.Rows) != 1 {
		t.Fatal(paged)
	}
	q.Limit = 100
	q.Offset = 0
	q.ExpenseCategory = "未分类"
	if got := st.ListTx(q); got.Total != 1 || got.Rows[0].ID != "unclassified" {
		t.Fatal(got)
	}
	q.ExpenseCategory = "正餐"
	q.Direction = "EXPENSE"
	if st.ListTx(q).Total != 2 || st.Stats(q).Expense != 12000 {
		t.Fatal("direction filter lost")
	}
	q.Direction = ""
	q.AccountID = "other"
	if st.ListTx(q).Total != 0 {
		t.Fatal("account filter lost")
	}
}
