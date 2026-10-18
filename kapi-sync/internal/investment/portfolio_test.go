package investment

import "testing"

func fixture(id, action, q string, cash, fee, at int64) Trade {
	return Trade{ID: id, AccountID: "calc", InstrumentCode: "110022", Name: "虚拟验收基金", Market: "FUND", Currency: "CNY", Action: action, Quantity: q, CashMinor: cash, FeeMinor: fee, OccurredAt: at}
}
func TestAndroidAcceptanceFundParity(t *testing.T) {
	ts := []Trade{fixture("b1", "BUY", "1000", 100000, 100, 1), fixture("b2", "BUY", "500", 60000, 50, 2), fixture("s1", "SELL", "600", 78000, 100, 3), fixture("d1", "DIVIDEND", "0", 3000, 0, 4)}
	ps, e := Positions(ts, map[string]Quote{"FUND:110022": {Price: "1.5"}})
	if e != nil {
		t.Fatal(e)
	}
	p := ps[0]
	if p.Quantity != "900" || p.CostMinor != 96090 || p.RealizedMinor != 16840 || p.CashDeltaMinor != -79250 || *p.ValueMinor != 135000 || *p.UnrealizedMinor != 38910 || p.AverageCost != "1.067667" {
		t.Fatalf("Android fixture mismatch: %+v", p)
	}
	lower, _ := Positions(ts, map[string]Quote{"FUND:110022": {Price: "1.4"}})
	if *lower[0].ValueMinor != 126000 || *lower[0].UnrealizedMinor != 29910 || lower[0].CostMinor != p.CostMinor {
		t.Fatal(lower)
	}
}
func TestAndroidAcceptanceStockParity(t *testing.T) {
	ts := []Trade{fixture("b1", "BUY", "100", 100000, 500, 1), fixture("b2", "BUY", "100", 120000, 500, 2), fixture("s", "SELL", "50", 65000, 300, 3)}
	for i := range ts {
		ts[i].Market = "SH"
		ts[i].InstrumentCode = "600519"
	}
	ps, e := Positions(ts, map[string]Quote{"SH:600519": {Price: "11"}})
	if e != nil {
		t.Fatal(e)
	}
	p := ps[0]
	if p.Quantity != "150" || p.CostMinor != 165750 || p.RealizedMinor != 9450 || *p.ValueMinor != 165000 || *p.UnrealizedMinor != -750 || p.CashDeltaMinor != -156300 {
		t.Fatalf("Android stock mismatch: %+v", p)
	}
}
func TestOpeningFractionalUnitsAndSplitKeepExactValue(t *testing.T) {
	ts := []Trade{fixture("o", "OPENING", "1250.125", 150015, 0, 1)}
	p, e := Positions(ts, map[string]Quote{"FUND:110022": {Price: "1.5"}})
	if e != nil {
		t.Fatal(e)
	}
	if *p[0].ValueMinor != 187519 || *p[0].UnrealizedMinor != 37504 || p[0].CashDeltaMinor != 0 || p[0].AverageCost != "1.200000" {
		t.Fatal(p)
	}
	ts = append(ts, fixture("s", "SPLIT", "1250.125", 0, 0, 2))
	p, e = Positions(ts, map[string]Quote{"FUND:110022": {Price: "0.75"}})
	if e != nil || p[0].Quantity != "2500.25" || p[0].CostMinor != 150015 || *p[0].ValueMinor != 187519 || p[0].CashDeltaMinor != 0 {
		t.Fatalf("split %+v %v", p, e)
	}
}
func TestFullSaleClearsRoundedCost(t *testing.T) {
	p, e := Positions([]Trade{fixture("b", "BUY", "3", 100, 1, 1), fixture("s1", "SELL", "1", 40, 0, 2), fixture("s2", "SELL", "2", 80, 0, 3)}, nil)
	if e != nil || p[0].Quantity != "0" || p[0].CostMinor != 0 || p[0].RealizedMinor != 19 || p[0].CashDeltaMinor != 19 {
		t.Fatalf("clearance %+v %v", p, e)
	}
}
func TestUnknownOrInvalidQuoteCannotTurnHoldingsIntoZero(t *testing.T) {
	for _, q := range []map[string]Quote{nil, {"FUND:110022": {Price: "0"}}, {"FUND:110022": {Price: "NaN"}}} {
		p, e := Positions([]Trade{fixture("b", "BUY", "100", 10000, 0, 1)}, q)
		if e != nil || p[0].ValueMinor != nil || p[0].UnrealizedMinor != nil {
			t.Fatalf("wrong unknown value %+v %v", p, e)
		}
	}
}
func TestInvalidSequencesAndAccountMixingAreRejected(t *testing.T) {
	buy := fixture("b", "BUY", "100", 10000, 0, 1)
	other := buy
	other.ID = "other"
	other.AccountID = "second"
	cases := [][]Trade{{buy, fixture("s", "SELL", "101", 11000, 0, 2)}, {fixture("s", "SELL", "1", 1, 0, 1)},
		{buy, buy}, {buy, other}, {buy, fixture("o", "OPENING", "100", 10000, 0, 2)},
		{fixture("o1", "OPENING", "100", 10000, 0, 1), fixture("o2", "OPENING", "100", 10000, 0, 2)},
		{fixture("d", "DIVIDEND", "0", 100, 0, 1)}, {fixture("s", "SPLIT", "100", 0, 0, 1)},
		{buy, fixture("s", "SELL", "1", 100, 101, 2)}}
	for i, ts := range cases {
		if _, e := Positions(ts, nil); e == nil {
			t.Fatalf("accepted invalid case %d", i)
		}
	}
}
func TestLargeAmountsAndEightDecimalUnitsStayExact(t *testing.T) {
	p, e := Positions([]Trade{fixture("o", "OPENING", "0.00000001", 99999999999, 0, 1)}, map[string]Quote{"FUND:110022": {Price: "99999999999"}})
	if e != nil || p[0].Quantity != "0.00000001" || *p[0].ValueMinor != 100000 || *p[0].UnrealizedMinor != -99999899999 {
		t.Fatalf("decimal %+v %v", p, e)
	}
	if _, e := Add(9223372036854775807, 1); e == nil {
		t.Fatal("integer overflow accepted")
	}
	for _, s := range []string{"NaN", "1e10", "-1", "1.123456789", "100000000000"} {
		if _, e := Quantity(s); e == nil {
			t.Fatal("invalid decimal", s)
		}
	}
}
