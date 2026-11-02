package store_test

import (
	"encoding/json"
	"kapi/sync/internal/store"
	"reflect"
	"testing"
)

// The existing generic document log transports mobile repo records without a
// server upgrade. Neither principal nor settlement becomes an ordinary txn.
func TestReverseRepoDocumentSyncDoesNotSpendPrincipalOrDuplicateInterest(t *testing.T) {
	s, push := assetFixture(t)
	push("account", "repo-account", fixtureAccount("投资", "INVESTMENT", 50000), "upsert")
	before := s.Assets(nil, "")
	value := map[string]any{"id": "repo-one", "accountId": "repo-account", "market": "SZ", "term_days": 1, "trade_date": "2026-01-08", "available_date": "2026-01-09", "principal_minor": 100000, "rate_percent": "1.335", "expected_interest_minor": 10, "reference": "", "source": "测试"}
	push("investment_repo", "repo-one", document(value), "upsert")
	value["actual_interest_minor"] = 8
	value["settled_date"] = "2026-01-09"
	push("investment_repo", "repo-one", document(value), "upsert")
	row, ok := s.Row("investment_repo", "repo-one")
	if !ok || row.Deleted {
		t.Fatal("repo record not retained")
	}
	var payload string
	if e := json.Unmarshal(row.Values["payload"], &payload); e != nil {
		t.Fatal(e)
	}
	var saved map[string]any
	if e := json.Unmarshal([]byte(payload), &saved); e != nil {
		t.Fatal(e)
	}
	if saved["actual_interest_minor"] != float64(8) || saved["principal_minor"] != float64(100000) {
		t.Fatalf("wrong settlement: %v", saved)
	}
	if !reflect.DeepEqual(before, s.Assets(nil, "")) {
		t.Fatal("repo history changed calibrated cash or stock valuation")
	}
	if len(s.ListTx(store.TxQuery{}).Rows) != 0 {
		t.Fatal("repo principal or interest became daily income/expense")
	}
	entries, _, _, err := s.Changes(0, 100)
	if err != nil || len(entries) != 3 {
		t.Fatalf("missing replayable changes: %d %v", len(entries), err)
	}
}
