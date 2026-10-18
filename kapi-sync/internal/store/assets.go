package store

import (
	"encoding/json"
	"sort"
	"strconv"
	"time"

	"kapi/sync/internal/investment"
)

type AccountView struct {
	ID               string `json:"id"`
	Name             string `json:"name"`
	Kind             string `json:"kind"`
	Currency         string `json:"currency"`
	LedgerName       string `json:"ledger_name"`
	Hidden           bool   `json:"hidden"`
	IncludeNetAssets bool   `json:"include_net_assets"`
	BalanceAsOf      *int64 `json:"balance_as_of"`
	CashMinor        *int64 `json:"cash_minor"`
	ValueMinor       *int64 `json:"value_minor"`
	PositionCount    int    `json:"position_count"`
	Error            string `json:"error,omitempty"`
	SortOrder        int64  `json:"-"`
}
type HoldingView struct {
	investment.Position
	AccountID   string `json:"account_id"`
	AccountName string `json:"account_name"`
}
type TradeView struct {
	investment.Trade
	AccountName string `json:"account_name"`
}
type AssetSummary struct {
	AssetsMinor      int64  `json:"assets_minor"`
	LiabilitiesMinor int64  `json:"liabilities_minor"`
	NetMinor         int64  `json:"net_minor"`
	IncludedAccounts int    `json:"included_accounts"`
	ExcludedAccounts int    `json:"excluded_accounts"`
	CostMinor        int64  `json:"cost_minor"`
	ValueMinor       *int64 `json:"value_minor"`
	UnrealizedMinor  *int64 `json:"unrealized_minor"`
	RealizedMinor    int64  `json:"realized_minor"`
	PositionCount    int    `json:"position_count"`
}
type AssetsView struct {
	Summary   AssetSummary  `json:"summary"`
	Accounts  []AccountView `json:"accounts"`
	Positions []HoldingView `json:"positions"`
	Trades    []TradeView   `json:"trades"`
	Issues    []string      `json:"issues"`
}

func optionalInt(raw json.RawMessage) *int64 {
	n, e := strconv.ParseInt(jsonStr(raw), 10, 64)
	if e != nil {
		return nil
	}
	return &n
}
func boolean(raw json.RawMessage, fallback bool) bool {
	v, e := strconv.ParseBool(jsonStr(raw))
	if e != nil {
		return fallback
	}
	return v
}

// Assets is a read-only projection: it cannot register quotes, write sync rows, or
// change transaction statistics. Ordinary cash movements follow Finance.balance.
func (s *Store) Assets(cache map[string]investment.Quote, accountID string) AssetsView {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := AssetsView{Accounts: []AccountView{}, Positions: []HoldingView{}, Trades: []TradeView{}, Issues: []string{}}
	trades := map[string][]investment.Trade{}
	tradeErrors := map[string]string{}
	unreadableTrade := false
	quotes := map[string]investment.Quote{}
	today := time.Now().In(time.FixedZone("Asia/Shanghai", 8*3600)).Format("2006-01-02")
	validQuote := func(q investment.Quote) bool {
		p, e := investment.Quantity(q.Price)
		_, d := time.Parse("2006-01-02", q.Date)
		return e == nil && p.Sign() > 0 && d == nil && q.Date <= today
	}
	for k, q := range cache {
		if k == q.Symbol && validQuote(q) {
			quotes[k] = q
		}
	}
	for _, r := range s.rows {
		if r.Deleted {
			continue
		}
		if r.Entity == "investment_trade" {
			var t investment.Trade
			if e := json.Unmarshal([]byte(jsonStr(r.Values["payload"])), &t); e != nil {
				unreadableTrade = true
				out.Issues = append(out.Issues, "存在无法读取的投资记录，请在手机上核对")
				continue
			}
			if t.Currency == "" {
				t.Currency = "CNY"
			}
			if t.ID != r.EntityID || investment.Validate(t) != nil {
				tradeErrors[t.AccountID] = "投资记录字段无效，请在手机上核对"
				out.Issues = append(out.Issues, "存在无效的投资记录，请在手机上核对")
				if t.AccountID == "" {
					unreadableTrade = true
				}
				continue
			}
			trades[t.AccountID] = append(trades[t.AccountID], t)
		} else if r.Entity == "investment_quote" {
			var q investment.Quote
			if json.Unmarshal([]byte(jsonStr(r.Values["payload"])), &q) != nil || q.Symbol != r.EntityID || !validQuote(q) {
				continue
			}
			old, ok := quotes[q.Symbol]
			if !ok || q.Date > old.Date || (q.Date == old.Date && q.FetchedAt >= old.FetchedAt) {
				quotes[q.Symbol] = q
			}
		}
	}
	names := s.ledgerNamesLocked()
	known := map[string]bool{}
	for _, r := range s.rows {
		if r.Entity != EntityAccount || r.Deleted || (accountID != "" && r.EntityID != accountID) {
			continue
		}
		a := AccountView{ID: r.EntityID, Name: jsonStr(r.Values["name"]), Kind: jsonStr(r.Values["kind"]), Currency: jsonStr(r.Values["currency"]),
			LedgerName: resolveName(names, jsonStr(r.Values["ledger_id"])), Hidden: boolean(r.Values["hidden"], false), IncludeNetAssets: boolean(r.Values["include_net_assets"], true),
			BalanceAsOf: optionalInt(r.Values["balance_as_of"]), SortOrder: jsonInt(r.Values["sort_order"])}
		if a.Name == "" {
			a.Name = a.ID
		}
		if a.Currency == "" {
			a.Currency = "CNY"
		}
		known[a.ID] = a.Kind == "INVESTMENT"
		opening := optionalInt(r.Values["balance_minor"])
		if a.BalanceAsOf != nil && opening != nil {
			cash := *opening
			var calcErr error
			for _, entry := range s.rows {
				if entry.Entity != TxnEntity || entry.Deleted {
					continue
				}
				t := readRecord(entry)
				if t.OccurredAt < *a.BalanceAsOf {
					continue
				}
				if t.ToAccountID != "" {
					if t.AccountID == a.ID {
						cash, calcErr = investment.Add(cash, -t.AmountMinor)
					}
					if calcErr == nil && t.ToAccountID == a.ID {
						cash, calcErr = investment.Add(cash, t.AmountMinor)
					}
				} else if t.AccountID == a.ID {
					if t.Direction == DirExpense {
						cash, calcErr = investment.Add(cash, -t.AmountMinor)
					} else if t.Direction == DirIncome {
						cash, calcErr = investment.Add(cash, t.AmountMinor)
					}
				}
				if calcErr != nil {
					break
				}
			}
			if calcErr != nil {
				a.Error = calcErr.Error()
			} else {
				a.CashMinor = &cash
				a.ValueMinor = &cash
			}
		}
		if a.Kind == "INVESTMENT" {
			events := trades[a.ID]
			positions, e := investment.Positions(events, quotes)
			if unreadableTrade {
				a.Error = "投资记录无法完整读取，请在手机上核对"
			} else if tradeErrors[a.ID] != "" {
				a.Error = tradeErrors[a.ID]
			} else if e != nil {
				a.Error = e.Error()
			}
			if a.Error != "" {
				a.ValueMinor = nil
				out.Issues = append(out.Issues, a.Name+"："+a.Error)
			} else {
				for _, t := range events {
					out.Trades = append(out.Trades, TradeView{t, a.Name})
				}
				// Cash calibration already includes earlier buys/sales; OPENING never deducts it.
				if a.CashMinor != nil {
					cash := *a.CashMinor
					for _, t := range events {
						if t.OccurredAt >= *a.BalanceAsOf {
							delta, e := investment.CashDelta(t)
							if e == nil {
								cash, e = investment.Add(cash, delta)
							}
							if e != nil {
								a.Error = e.Error()
								break
							}
						}
					}
					if a.Error == "" {
						a.CashMinor = &cash
						a.ValueMinor = &cash
					} else {
						a.CashMinor = nil
						a.ValueMinor = nil
					}
				}
				for _, p := range positions {
					out.Positions = append(out.Positions, HoldingView{p, a.ID, a.Name})
					units, _ := investment.Quantity(p.Quantity)
					if units.Sign() > 0 {
						a.PositionCount++
						if a.ValueMinor != nil {
							if p.ValueMinor == nil {
								a.ValueMinor = nil
							} else {
								n, e := investment.Add(*a.ValueMinor, *p.ValueMinor)
								if e != nil {
									a.Error = e.Error()
									a.ValueMinor = nil
								} else {
									a.ValueMinor = &n
								}
							}
						}
					}
				}
			}
		}
		out.Accounts = append(out.Accounts, a)
	}
	for id := range trades {
		if !known[id] && (accountID == "" || id == accountID) {
			out.Issues = append(out.Issues, "部分投资记录的账户已删除或尚未同步，不计入持仓合计")
		}
	}
	sort.Slice(out.Accounts, func(i, j int) bool {
		a, b := out.Accounts[i], out.Accounts[j]
		if a.SortOrder != b.SortOrder {
			return a.SortOrder < b.SortOrder
		}
		return a.ID < b.ID
	})
	sort.Slice(out.Positions, func(i, j int) bool {
		a, b := out.Positions[i], out.Positions[j]
		if a.AccountID != b.AccountID {
			return a.AccountID < b.AccountID
		}
		return a.Symbol < b.Symbol
	})
	sort.Slice(out.Trades, func(i, j int) bool {
		a, b := out.Trades[i], out.Trades[j]
		if a.OccurredAt != b.OccurredAt {
			return a.OccurredAt > b.OccurredAt
		}
		return a.ID < b.ID
	})
	var market, gain int64
	complete := len(out.Issues) == 0
	safeAdd := func(a *int64, b int64) {
		n, e := investment.Add(*a, b)
		if e != nil {
			out.Issues = append(out.Issues, e.Error())
			complete = false
		} else {
			*a = n
		}
	}
	for _, a := range out.Accounts {
		if a.ValueMinor == nil || !a.IncludeNetAssets || a.Currency != "CNY" || a.Kind == "VIRTUAL" {
			out.Summary.ExcludedAccounts++
			continue
		}
		out.Summary.IncludedAccounts++
		if *a.ValueMinor >= 0 {
			safeAdd(&out.Summary.AssetsMinor, *a.ValueMinor)
		} else {
			safeAdd(&out.Summary.LiabilitiesMinor, -*a.ValueMinor)
		}
	}
	out.Summary.NetMinor, _ = investment.Add(out.Summary.AssetsMinor, -out.Summary.LiabilitiesMinor)
	for _, p := range out.Positions {
		safeAdd(&out.Summary.CostMinor, p.CostMinor)
		safeAdd(&out.Summary.RealizedMinor, p.RealizedMinor)
		q, _ := investment.Quantity(p.Quantity)
		if q.Sign() > 0 {
			out.Summary.PositionCount++
		}
		if p.ValueMinor == nil {
			if q.Sign() > 0 {
				complete = false
			}
		} else {
			safeAdd(&market, *p.ValueMinor)
			safeAdd(&gain, *p.UnrealizedMinor)
		}
	}
	if complete {
		out.Summary.ValueMinor = &market
		out.Summary.UnrealizedMinor = &gain
	}
	return out
}
