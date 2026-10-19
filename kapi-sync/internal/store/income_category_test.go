package store_test

import (
	"encoding/json"
	"testing"
	"time"

	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
)

func TestIncomeCompositionAndDrillUseSamePopulation(t *testing.T) {
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	var changes []merge.Change
	for _, id := range []string{"salary-a", "salary-b"} {
		c := upsert(id, int64(len(changes)+1), 0, "", "", "", "", "", 0)
		c.Entity = "category"
		c.Data = map[string]json.RawMessage{"name": merge.V("工资")}
		changes = append(changes, c)
	}
	add := func(id string, n int64, dir, cat, book, acct string, at int64, extra map[string]json.RawMessage) {
		c := upsert(id, int64(len(changes)+10), n, dir, cat, acct, "测试公司", "", at)
		c.Data["ledger_id"] = merge.V(book)
		for k, v := range extra {
			c.Data[k] = v
		}
		changes = append(changes, c)
	}
	at := ts(2026, time.September, 10, 12)
	add("salary-1", 800000, "INCOME", "salary-a", "L1", "bank", at, nil)
	add("salary-2", 200000, "INCOME", "salary-b", "L1", "bank", at+1, nil)
	add("bonus", 60000, "INCOME", "奖金", "L1", "bank", at+2, nil)
	add("uncategorized", 5000, "INCOME", "", "L1", "bank", at+3, nil)
	add("other-book", 999999, "INCOME", "salary-a", "L2", "bank", at, nil)
	add("old", 300000, "INCOME", "salary-a", "L1", "bank", ts(2026, time.August, 31, 12), nil)
	add("another-account", 3000, "INCOME", "salary-a", "L1", "cash", at, nil)
	add("meal", 20000, "EXPENSE", "餐饮", "L1", "bank", at, nil)
	add("refund", 10000, "INCOME", "salary-a", "L1", "bank", at, map[string]json.RawMessage{"refund_of_id": merge.V("meal")})
	add("transfer", 80000, "INCOME", "salary-a", "L1", "bank", at, map[string]json.RawMessage{"to_account_id": merge.V("cash")})
	add("zero", 0, "INCOME", "salary-a", "L1", "bank", at, nil)
	add("deleted", 1000000, "INCOME", "salary-a", "L1", "bank", at, nil)
	changes = append(changes, merge.Change{ChangeID: "delete-income", Entity: "txn", EntityID: "deleted", Op: merge.OpDelete, Lamport: 100, Origin: "dev_test"})
	if _, err := st.Push("dev_test", changes); err != nil {
		t.Fatal(err)
	}
	q := store.TxQuery{LedgerID: "L1", AccountID: "bank", From: ts(2026, time.September, 1, 0), To: ts(2026, time.October, 1, 0), IncludeDeleted: true}
	stats := st.Stats(q)
	if stats.Income != 1065000 || stats.Expense != 10000 || len(stats.ByIncomeCategory) != 3 {
		t.Fatalf("income/refund composition: %+v", stats)
	}
	before := st.HealthInfo()
	var grand int64
	for _, bucket := range stats.ByIncomeCategory {
		grand += bucket.Minor
		drill := q
		drill.IncomeCategory = bucket.Key
		page := st.ListTx(drill)
		total := st.Stats(drill)
		var sum int64
		for _, r := range page.Rows {
			if r.Direction != "INCOME" || r.RefundOfID != "" || r.ToAccountID != "" || r.Deleted {
				t.Fatalf("unexpected row: %+v", r)
			}
			sum += r.AmountMinor
		}
		if page.Total != bucket.Count || sum != bucket.Minor || total.Income != sum || total.Count != page.Total || total.Expense != 0 {
			t.Fatalf("bucket/list/stats mismatch: %+v %+v %+v", bucket, page, total)
		}
	}
	if grand != stats.Income {
		t.Fatal("income buckets do not reconcile")
	}
	q.IncomeCategory = "工资"
	q.Limit = 1
	q.Offset = 1
	if page := st.ListTx(q); page.Total != 2 || len(page.Rows) != 1 {
		t.Fatal(page)
	}
	q.IncomeCategory = "未分类"
	q.Offset = 0
	if page := st.ListTx(q); page.Total != 1 || page.Rows[0].ID != "uncategorized" {
		t.Fatal(page)
	}
	q.IncomeCategory = "工资"
	q.Direction = "EXPENSE"
	if st.ListTx(q).Total != 0 || st.Stats(q).Income != 0 {
		t.Fatal("direction constraint lost")
	}
	q.Direction = ""
	q.ExpenseCategory = "餐饮"
	if st.ListTx(q).Total != 0 || st.Stats(q).Count != 0 {
		t.Fatal("conflicting category constraints broadened query")
	}
	after := st.HealthInfo()
	if before != after {
		t.Fatal("read-only drill modified store")
	}
}
