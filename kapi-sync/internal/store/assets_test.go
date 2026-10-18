package store_test

import (
	"encoding/json"
	"fmt"
	"kapi/sync/internal/investment"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"testing"
	"time"
)

func assetFixture(t *testing.T) (*store.Store, func(string, string, map[string]json.RawMessage, string)) {
	t.Helper()
	s, e := store.Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	code, _ := s.PairCode()
	d, e := s.Pair(code, "asset-fixture")
	if e != nil {
		t.Fatal(e)
	}
	var stamp int64
	push := func(entity, id string, data map[string]json.RawMessage, op string) {
		stamp++
		_, e := s.Push(d.DeviceID, []merge.Change{{ChangeID: fmt.Sprint(stamp), Entity: entity, EntityID: id, Data: data, Op: op, Lamport: stamp, Origin: d.DeviceID}})
		if e != nil {
			t.Fatal(e)
		}
	}
	return s, push
}
func fixtureAccount(name, kind string, balance int64) map[string]json.RawMessage {
	return map[string]json.RawMessage{"name": merge.V(name), "kind": merge.V(kind), "balance_minor": merge.V(balance), "balance_as_of": merge.V(0), "currency": merge.V("CNY")}
}
func assetTrade(id, account, action, quantity string, cash, fee, at int64) investment.Trade {
	return investment.Trade{ID: id, AccountID: account, InstrumentCode: "110022", Name: "虚拟基金", Market: "FUND", Currency: "CNY", Action: action, Quantity: quantity, CashMinor: cash, FeeMinor: fee, OccurredAt: at}
}
func document(t any) map[string]json.RawMessage {
	b, _ := json.Marshal(t)
	return map[string]json.RawMessage{"payload": merge.V(string(b))}
}
func TestAssetsAndroidFundCalculationAndReadOnlySyncState(t *testing.T) {
	s, push := assetFixture(t)
	push("account", "calc", fixtureAccount("虚拟基金账户", "INVESTMENT", 500000), "upsert")
	for _, v := range []investment.Trade{assetTrade("b1", "calc", "BUY", "1000", 100000, 100, 1), assetTrade("b2", "calc", "BUY", "500", 60000, 50, 2), assetTrade("s", "calc", "SELL", "600", 78000, 100, 3), assetTrade("d", "calc", "DIVIDEND", "0", 3000, 0, 4)} {
		push("investment_trade", v.ID, document(v), "upsert")
	}
	push("investment_quote", "FUND:110022", document(investment.Quote{Symbol: "FUND:110022", Price: "1.5", Date: "2026-10-01", Source: "固定测试报价"}), "upsert")
	before := s.HealthInfo()
	a := s.Assets(nil, "")
	if a.Summary.NetMinor != 555750 || *a.Accounts[0].CashMinor != 420750 || a.Positions[0].CostMinor != 96090 || *a.Summary.ValueMinor != 135000 || *a.Summary.UnrealizedMinor != 38910 || a.Summary.RealizedMinor != 16840 {
		t.Fatalf("mobile parity: %+v", a)
	}
	if s.HealthInfo() != before || s.Stats(store.TxQuery{}).Count != 0 {
		t.Fatal("read model wrote data or counted investment as spending")
	}
}
func TestAccountCashTransfersDebtHiddenAndExcludedCurrency(t *testing.T) {
	s, push := assetFixture(t)
	a := fixtureAccount("现金", "CASH", 100000)
	a["hidden"] = merge.V(true)
	push("account", "cash", a, "upsert")
	push("account", "bank", fixtureAccount("银行卡", "DEBIT", 0), "upsert")
	push("account", "debt", fixtureAccount("信用卡", "CREDIT", -50000), "upsert")
	foreign := fixtureAccount("美元", "DEBIT", 10000)
	foreign["currency"] = merge.V("USD")
	push("account", "usd", foreign, "upsert")
	unset := fixtureAccount("未校准", "CASH", 10000)
	unset["balance_as_of"] = merge.V(nil)
	push("account", "unset", unset, "upsert")
	skipped := fixtureAccount("不计入", "CASH", 20000)
	skipped["include_net_assets"] = merge.V(false)
	push("account", "skip", skipped, "upsert")
	push("txn", "transfer", map[string]json.RawMessage{"account_id": merge.V("cash"), "to_account_id": merge.V("bank"), "amount_minor": merge.V(10000), "direction": merge.V("EXPENSE"), "occurred_at": merge.V(10)}, "upsert")
	push("txn", "meal", map[string]json.RawMessage{"account_id": merge.V("cash"), "amount_minor": merge.V(2000), "direction": merge.V("EXPENSE"), "occurred_at": merge.V(11)}, "upsert")
	view := s.Assets(nil, "")
	if view.Summary.AssetsMinor != 98000 || view.Summary.LiabilitiesMinor != 50000 || view.Summary.NetMinor != 48000 || view.Summary.ExcludedAccounts != 3 {
		t.Fatal(view.Summary)
	}
	filtered := s.Assets(nil, "cash")
	if len(filtered.Accounts) != 1 || *filtered.Accounts[0].CashMinor != 88000 || !filtered.Accounts[0].Hidden {
		t.Fatal(filtered)
	}
}
func TestMissingQuotesCannotReduceInvestmentToCashOnly(t *testing.T) {
	s, push := assetFixture(t)
	push("account", "calc", fixtureAccount("投资", "INVESTMENT", 500000), "upsert")
	v := assetTrade("o", "calc", "OPENING", "1250.125", 150015, 0, 1)
	push("investment_trade", v.ID, document(v), "upsert")
	a := s.Assets(nil, "")
	if a.Accounts[0].ValueMinor != nil || a.Summary.ValueMinor != nil || a.Summary.UnrealizedMinor != nil || a.Summary.ExcludedAccounts != 1 || *a.Accounts[0].CashMinor != 500000 {
		t.Fatal(a)
	}
	cache := map[string]investment.Quote{"FUND:110022": {Symbol: "FUND:110022", Price: "1.5", Date: "2026-10-01", Source: "NAS 缓存"}}
	a = s.Assets(cache, "")
	if a.Summary.NetMinor != 687519 || *a.Positions[0].UnrealizedMinor != 37504 {
		t.Fatal(a)
	}
}
func TestCashCalibrationAndSameFundAccountsDoNotMix(t *testing.T) {
	s, push := assetFixture(t)
	push("account", "a", fixtureAccount("账户A", "INVESTMENT", 500000), "upsert")
	b := fixtureAccount("账户B", "INVESTMENT", 200000)
	b["balance_as_of"] = merge.V(2)
	push("account", "b", b, "upsert")
	for _, v := range []investment.Trade{assetTrade("a", "a", "BUY", "100", 10000, 0, 1), assetTrade("b", "b", "BUY", "200", 30000, 0, 1)} {
		push("investment_trade", v.ID, document(v), "upsert")
	}
	cache := map[string]investment.Quote{"FUND:110022": {Symbol: "FUND:110022", Price: "2", Date: "2026-10-01"}}
	a := s.Assets(cache, "a")
	bView := s.Assets(cache, "b")
	if a.Summary.NetMinor != 510000 || bView.Summary.NetMinor != 240000 || len(a.Trades) != 1 || len(bView.Trades) != 1 {
		t.Fatal(a, bView)
	}
}
func TestTombstonesInvalidHoldingsAndOrphansNeverInventZeroValues(t *testing.T) {
	s, push := assetFixture(t)
	push("account", "calc", fixtureAccount("投资", "INVESTMENT", 500000), "upsert")
	buy := assetTrade("b", "calc", "BUY", "100", 10000, 0, 1)
	sale := assetTrade("s", "calc", "SELL", "20", 3000, 0, 2)
	push("investment_trade", buy.ID, document(buy), "upsert")
	push("investment_trade", sale.ID, document(sale), "upsert")
	push("investment_trade", buy.ID, nil, "delete")
	a := s.Assets(nil, "")
	if len(a.Issues) == 0 || a.Accounts[0].ValueMinor != nil || len(a.Positions) != 0 {
		t.Fatal(a)
	}
	push("investment_trade", sale.ID, nil, "delete")
	a = s.Assets(nil, "")
	if len(a.Trades) != 0 || *a.Accounts[0].ValueMinor != 500000 {
		t.Fatal(a)
	}
	orphan := assetTrade("orphan", "missing", "BUY", "100", 10000, 0, 1)
	push("investment_trade", orphan.ID, document(orphan), "upsert")
	a = s.Assets(nil, "")
	if len(a.Issues) == 0 || len(a.Positions) != 0 {
		t.Fatal(a)
	}
}
func TestBadPayloadAndInvalidPricesRemainVisibleAsUncertainty(t *testing.T) {
	s, push := assetFixture(t)
	push("account", "calc", fixtureAccount("投资", "INVESTMENT", 500000), "upsert")
	push("investment_trade", "broken", map[string]json.RawMessage{"payload": merge.V("invalid-json")}, "upsert")
	a := s.Assets(nil, "")
	if a.Accounts[0].ValueMinor != nil || len(a.Issues) == 0 {
		t.Fatal(a)
	}
	push("investment_trade", "broken", nil, "delete")
	v := assetTrade("o", "calc", "OPENING", "100", 10000, 0, 1)
	push("investment_trade", v.ID, document(v), "upsert")
	for _, q := range []investment.Quote{{Symbol: "FUND:110022", Price: "0", Date: "2026-10-01"}, {Symbol: "FUND:110022", Price: "2", Date: time.Now().Add(48 * time.Hour).Format("2006-01-02")}} {
		a = s.Assets(map[string]investment.Quote{q.Symbol: q}, "")
		if a.Positions[0].ValueMinor != nil {
			t.Fatal("invalid quote valued holdings", q)
		}
	}
}
func TestLatestSuccessfulQuoteAndStaleErrorAreDisplayedWithoutWrites(t *testing.T) {
	s, push := assetFixture(t)
	push("account", "calc", fixtureAccount("投资", "INVESTMENT", 500000), "upsert")
	v := assetTrade("o", "calc", "OPENING", "100", 10000, 0, 1)
	push("investment_trade", v.ID, document(v), "upsert")
	push("investment_quote", "FUND:110022", document(investment.Quote{Symbol: "FUND:110022", Price: "1", Date: "2026-10-01", FetchedAt: 1}), "upsert")
	q := investment.Quote{Symbol: "FUND:110022", Price: "2", Date: "2026-10-02", FetchedAt: 2, Error: "更新暂时失败", Source: "NAS 缓存"}
	a := s.Assets(map[string]investment.Quote{q.Symbol: q}, "")
	if *a.Positions[0].ValueMinor != 20000 || a.Positions[0].Quote.Error != q.Error {
		t.Fatal(a)
	}
}
