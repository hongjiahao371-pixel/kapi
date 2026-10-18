package store_test

import (
	"encoding/json"
	"fmt"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"testing"
)

func TestRefundAndTransfersUseNetExpense(t *testing.T) {
	st, _ := store.Open(t.TempDir())
	defer st.Close()
	code, _ := st.PairCode()
	device, _ := st.Pair(code, "finance")
	rows := []map[string]json.RawMessage{
		{"amount_minor": merge.V(10000), "direction": merge.V("EXPENSE"), "category_id": merge.V("meal")},
		{"amount_minor": merge.V(3000), "direction": merge.V("INCOME"), "refund_of_id": merge.V("0")},
		{"amount_minor": merge.V(2000), "direction": merge.V("INCOME")},
		{"amount_minor": merge.V(50000), "direction": merge.V("EXPENSE"), "to_account_id": merge.V("second")},
	}
	for i, data := range rows {
		id := fmt.Sprint(i)
		_, err := st.Push(device.DeviceID, []merge.Change{{ChangeID: id, Entity: "txn", EntityID: id, Op: "upsert", Data: data, Lamport: int64(i + 1), Origin: device.DeviceID}})
		if err != nil {
			t.Fatal(err)
		}
	}
	stats := st.Stats(store.TxQuery{})
	if stats.Expense != 7000 || stats.Income != 2000 || stats.Balance != -5000 {
		t.Fatalf("wrong totals: %+v", stats)
	}
	if len(stats.ByCategory) != 1 || stats.ByCategory[0].Minor != 7000 {
		t.Fatalf("refund category lost: %+v", stats.ByCategory)
	}
}
func TestTransferAndUnknownHaveZeroSignedAmount(t *testing.T) {
	for _, row := range []store.TxView{{AmountMinor: 100, Direction: store.DirExpense, ToAccountID: "A2"}, {AmountMinor: 100, Direction: "UNKNOWN"}} {
		if row.SignedMinor() != 0 {
			t.Fatal("neutral transaction affected totals")
		}
	}
}
