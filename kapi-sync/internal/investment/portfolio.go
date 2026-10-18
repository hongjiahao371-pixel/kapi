// Package investment derives read-only positions using the Android accounting rules.
// Quantities/prices are exact rationals, money is integer cents, with half-up rounding.
package investment

import (
	"errors"
	"fmt"
	"math/big"
	"regexp"
	"sort"
)

type Trade struct {
	ID             string `json:"id"`
	AccountID      string `json:"account_id"`
	InstrumentCode string `json:"instrument_code"`
	Name           string `json:"name"`
	Market         string `json:"market"`
	Currency       string `json:"currency"`
	Action         string `json:"action"`
	Quantity       string `json:"quantity"`
	CashMinor      int64  `json:"cash_minor"`
	FeeMinor       int64  `json:"fee_minor"`
	OccurredAt     int64  `json:"occurred_at"`
	CostNote       string `json:"cost_note"`
}
type Quote struct {
	Symbol    string `json:"symbol"`
	Price     string `json:"price"`
	Date      string `json:"date"`
	Source    string `json:"source"`
	FetchedAt int64  `json:"fetched_at"`
	Error     string `json:"error,omitempty"`
}
type Position struct {
	Symbol          string `json:"symbol"`
	Name            string `json:"name"`
	Currency        string `json:"currency"`
	Quantity        string `json:"quantity"`
	CostMinor       int64  `json:"cost_minor"`
	RealizedMinor   int64  `json:"realized_minor"`
	CashDeltaMinor  int64  `json:"cash_delta_minor"`
	ValueMinor      *int64 `json:"value_minor"`
	UnrealizedMinor *int64 `json:"unrealized_minor"`
	AverageCost     string `json:"average_cost,omitempty"`
	Quote           *Quote `json:"quote,omitempty"`
	CostNote        string `json:"cost_note,omitempty"`
}

var decimal = regexp.MustCompile(`^[0-9]+(?:\.[0-9]{1,8})?$`)
var code = regexp.MustCompile(`^[0-9]{6}$`)

func Quantity(s string) (*big.Rat, error) {
	if !decimal.MatchString(s) {
		return nil, errors.New("份额或报价格式无效")
	}
	r, ok := new(big.Rat).SetString(s)
	if !ok || r.Sign() < 0 || r.Cmp(big.NewRat(99999999999, 1)) > 0 {
		return nil, errors.New("份额或报价超出范围")
	}
	return r, nil
}
func Add(a, b int64) (int64, error) {
	n := new(big.Int).Add(big.NewInt(a), big.NewInt(b))
	if !n.IsInt64() {
		return 0, errors.New("金额合计超出范围")
	}
	return n.Int64(), nil
}
func rounded(r *big.Rat) (int64, error) {
	n := new(big.Int).Abs(r.Num())
	d := r.Denom()
	q, rem := new(big.Int), new(big.Int)
	q.QuoRem(n, d, rem)
	if rem.Mul(rem, big.NewInt(2)).Cmp(d) >= 0 {
		q.Add(q, big.NewInt(1))
	}
	if r.Sign() < 0 {
		q.Neg(q)
	}
	if !q.IsInt64() {
		return 0, errors.New("金额计算超出范围")
	}
	return q.Int64(), nil
}
func Validate(t Trade) error {
	q, e := Quantity(t.Quantity)
	if e != nil {
		return e
	}
	if t.Currency == "" {
		t.Currency = "CNY"
	}
	if t.ID == "" || t.AccountID == "" || t.Name == "" || !code.MatchString(t.InstrumentCode) ||
		(t.Market != "FUND" && t.Market != "SH" && t.Market != "SZ") || t.Currency != "CNY" ||
		t.CashMinor < 0 || t.CashMinor > 99999999999 || t.FeeMinor < 0 || t.FeeMinor > 99999999999 || t.OccurredAt <= 0 {
		return errors.New("投资记录字段无效")
	}
	switch t.Action {
	case "OPENING":
		if q.Sign() <= 0 || t.FeeMinor != 0 {
			return errors.New("期初持仓份额或成本无效")
		}
	case "BUY", "SELL":
		if q.Sign() <= 0 || t.CashMinor <= 0 || (t.Action == "SELL" && t.FeeMinor > t.CashMinor) {
			return errors.New("买卖金额或手续费无效")
		}
	case "DIVIDEND":
		if q.Sign() != 0 || t.CashMinor <= 0 || t.FeeMinor > t.CashMinor {
			return errors.New("现金分红记录无效")
		}
	case "SPLIT":
		if q.Sign() <= 0 || t.CashMinor != 0 || t.FeeMinor != 0 {
			return errors.New("份额拆分记录无效")
		}
	default:
		return errors.New("投资交易类型无效")
	}
	return nil
}
func CashDelta(t Trade) (int64, error) {
	switch t.Action {
	case "BUY":
		n, e := Add(t.CashMinor, t.FeeMinor)
		return -n, e
	case "SELL", "DIVIDEND":
		return Add(t.CashMinor, -t.FeeMinor)
	}
	return 0, nil
}
func Positions(trades []Trade, quotes map[string]Quote) ([]Position, error) {
	groups := map[string][]Trade{}
	ids := map[string]bool{}
	account := ""
	for _, t := range trades {
		if e := Validate(t); e != nil {
			return nil, e
		}
		if ids[t.ID] {
			return nil, errors.New("投资交易标识重复")
		}
		ids[t.ID] = true
		if account != "" && account != t.AccountID {
			return nil, errors.New("持仓计算必须按投资账户分开")
		}
		account = t.AccountID
		groups[t.Market+":"+t.InstrumentCode] = append(groups[t.Market+":"+t.InstrumentCode], t)
	}
	symbols := []string{}
	for s := range groups {
		symbols = append(symbols, s)
	}
	sort.Strings(symbols)
	result := []Position{}
	for _, symbol := range symbols {
		events := groups[symbol]
		sort.Slice(events, func(i, j int) bool {
			if events[i].OccurredAt == events[j].OccurredAt {
				return events[i].ID < events[j].ID
			}
			return events[i].OccurredAt < events[j].OccurredAt
		})
		q := new(big.Rat)
		p := Position{Symbol: symbol, Name: events[len(events)-1].Name, Currency: "CNY"}
		openings := 0
		for i, t := range events {
			units, _ := Quantity(t.Quantity)
			delta, e := CashDelta(t)
			if e != nil {
				return nil, e
			}
			switch t.Action {
			case "OPENING":
				openings++
				if openings > 1 || i != 0 {
					return nil, errors.New("期初持仓必须在该资产交易之前且只能有一次")
				}
				q.Set(units)
				p.CostMinor = t.CashMinor
				p.CostNote = t.CostNote
			case "BUY":
				q.Add(q, units)
				p.CostMinor, e = Add(p.CostMinor, -delta)
			case "SELL":
				if units.Cmp(q) > 0 {
					return nil, fmt.Errorf("%s 卖出份额超过当时持仓", t.Name)
				}
				allocated := p.CostMinor
				if units.Cmp(q) != 0 {
					allocated, e = rounded(new(big.Rat).Quo(new(big.Rat).Mul(big.NewRat(p.CostMinor, 1), units), q))
					if e != nil {
						return nil, e
					}
				}
				q.Sub(q, units)
				p.CostMinor, e = Add(p.CostMinor, -allocated)
				if e == nil {
					p.RealizedMinor, e = Add(p.RealizedMinor, delta-allocated)
				}
			case "DIVIDEND":
				if q.Sign() <= 0 {
					return nil, errors.New("没有持仓时不能记录分红")
				}
				p.RealizedMinor, e = Add(p.RealizedMinor, delta)
			case "SPLIT":
				if q.Sign() <= 0 {
					return nil, errors.New("没有持仓时不能增加拆分份额")
				}
				q.Add(q, units)
			}
			if e != nil {
				return nil, e
			}
			p.CashDeltaMinor, e = Add(p.CashDeltaMinor, delta)
			if e != nil {
				return nil, e
			}
			if q.Cmp(big.NewRat(99999999999, 1)) > 0 {
				return nil, errors.New("持仓份额超出范围")
			}
		}
		p.Quantity = q.FloatString(8)
		for len(p.Quantity) > 0 && p.Quantity[len(p.Quantity)-1] == '0' {
			p.Quantity = p.Quantity[:len(p.Quantity)-1]
		}
		if p.Quantity[len(p.Quantity)-1] == '.' {
			p.Quantity = p.Quantity[:len(p.Quantity)-1]
		}
		if q.Sign() > 0 {
			p.AverageCost = new(big.Rat).Quo(big.NewRat(p.CostMinor, 100), q).FloatString(6)
		}
		if quote, ok := quotes[symbol]; ok {
			p.Quote = &quote
			price, e := Quantity(quote.Price)
			if e == nil && price.Sign() > 0 {
				value, e := rounded(new(big.Rat).Mul(new(big.Rat).Mul(price, q), big.NewRat(100, 1)))
				if e != nil {
					return nil, e
				}
				gain, e := Add(value, -p.CostMinor)
				if e != nil {
					return nil, e
				}
				p.ValueMinor = &value
				p.UnrealizedMinor = &gain
			}
		}
		result = append(result, p)
	}
	return result, nil
}
