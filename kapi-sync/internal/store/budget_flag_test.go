package store

import (
	"kapi/sync/internal/merge"
	"testing"
)

func TestBudgetExclusionSurvivesOlderClientEditRestartAndImportSnapshot(t *testing.T) {
	s, dir := newTestStore(t)
	c := txn("phone", "budget-proof", 10, 1235)
	c.Data["exclude_from_budget"] = merge.V(true)
	c.FV["exclude_from_budget"] = merge.FVEntry{L: 10, D: "phone"}
	if _, err := s.Push("dev_a", []merge.Change{c}); err != nil {
		t.Fatal(err)
	}
	// An old client edits an existing field without knowing the added budget field.
	if _, err := s.Push("dev_b", []merge.Change{txn("older-client", "budget-proof", 20, 1235)}); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	restored, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	row, ok := restored.Row("txn", "budget-proof")
	if !ok {
		t.Fatal("missing bill")
	}
	if string(row.Values["exclude_from_budget"]) != "true" {
		t.Fatal("lost budget exclusion")
	}
	_, snapshot, _ := restored.BillImportSnapshot("L1")
	if len(snapshot) != 1 || !snapshot[0].Row.ExcludeFromBudget {
		t.Fatal("import snapshot dropped budget exclusion")
	}
	if string(row.Values["amount_minor"]) != "1235" {
		t.Fatal("money changed")
	}
}
