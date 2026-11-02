package commerce

import (
	"context"
	"crypto/md5" // The official open-API request protocol mandates MD5, not our entitlement signatures.
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type AfdianConfig struct {
	UserID, Token, PlanID, SKUID string
	ProductType                  int // 1: one-time sale; 0: an explicitly confirmed permanent plan.
	PermanentPlanConfirmed       bool
}
type Afdian struct {
	cfg    AfdianConfig
	apiURL string
	client *http.Client
	now    func() time.Time
}

func NewAfdian(c AfdianConfig) (*Afdian, error) {
	if !validAtom(c.UserID, 32, 32) || !validAtom(c.Token, 20, 200) || !validAtom(c.PlanID, 32, 32) || (c.ProductType != 0 && c.ProductType != 1) || (c.ProductType == 1 && !validAtom(c.SKUID, 32, 32)) || (c.ProductType == 0 && !c.PermanentPlanConfirmed) {
		return nil, ErrInvalid
	}
	return &Afdian{cfg: c, apiURL: "https://afdian.com/api/open/query-order", client: fixedClient(), now: time.Now}, nil
}
func (a *Afdian) Name() string { return "afdian" }
func (a *Afdian) Prepare(_ context.Context, o Order) (Checkout, error) {
	if o.Platform != "android" || o.AmountMinor != 2990 || o.Currency != "CNY" || !validAtom(o.ID, 32, 32) {
		return Checkout{}, ErrInvalid
	}
	v := url.Values{"plan_id": {a.cfg.PlanID}, "product_type": {strconv.Itoa(a.cfg.ProductType)}, "custom_order_id": {o.ID}, "remark": {"归账永久 Pro · " + o.ID}}
	if a.cfg.ProductType == 0 {
		v.Set("month", "1")
	}
	return Checkout{Kind: "redirect", Value: "https://afdian.com/order/create?" + v.Encode()}, nil
}

type afdianOrder struct {
	TradeNo     string `json:"out_trade_no"`
	CustomID    string `json:"custom_order_id"`
	PlanID      string `json:"plan_id"`
	Total       string `json:"total_amount"`
	Status      int    `json:"status"`
	ProductType int    `json:"product_type"`
	Month       int    `json:"month"`
	RedeemID    string `json:"redeem_id"`
	SKU         []struct {
		ID    string `json:"sku_id"`
		Count int    `json:"count"`
	} `json:"sku_detail"`
}

func (a *Afdian) query(ctx context.Context, params any) ([]afdianOrder, error) {
	p, err := json.Marshal(params)
	if err != nil {
		return nil, ErrInvalid
	}
	ts := a.now().Unix()
	sum := md5.Sum([]byte(a.cfg.Token + "params" + string(p) + "ts" + strconv.FormatInt(ts, 10) + "user_id" + a.cfg.UserID))
	body, _ := json.Marshal(struct {
		User   string `json:"user_id"`
		Params string `json:"params"`
		TS     int64  `json:"ts"`
		Sign   string `json:"sign"`
	}{a.cfg.UserID, string(p), ts, hex.EncodeToString(sum[:])})
	req, err := http.NewRequestWithContext(ctx, "POST", a.apiURL, strings.NewReader(string(body)))
	if err != nil {
		return nil, ErrInvalid
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := a.client.Do(req)
	if err != nil {
		return nil, ErrInvalid
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, ErrInvalid
	}
	b, err := io.ReadAll(io.LimitReader(res.Body, (1<<20)+1))
	if err != nil || len(b) > 1<<20 {
		return nil, ErrInvalid
	}
	var answer struct {
		EC   int `json:"ec"`
		Data struct {
			List []afdianOrder `json:"list"`
		} `json:"data"`
	}
	if json.Unmarshal(b, &answer) != nil || answer.EC != 200 || len(answer.Data.List) > 100 {
		return nil, ErrInvalid
	}
	return answer.Data.List, nil
}
func (a *Afdian) verified(o Order, paid afdianOrder) (Receipt, error) {
	amount, err := minor(paid.Total)
	if err != nil || paid.Status != 2 || paid.CustomID != o.ID || paid.PlanID != a.cfg.PlanID || paid.ProductType != a.cfg.ProductType || amount != o.AmountMinor || o.Currency != "CNY" || paid.RedeemID != "" || !validAtom(paid.TradeNo, 12, 64) {
		return Receipt{}, ErrConflict
	}
	if paid.ProductType == 1 && (len(paid.SKU) != 1 || paid.SKU[0].ID != a.cfg.SKUID || paid.SKU[0].Count != 1) {
		return Receipt{}, ErrConflict
	}
	if paid.ProductType == 0 && paid.Month != 1 {
		return Receipt{}, ErrConflict
	}
	return Receipt{OrderID: o.ID, TradeID: paid.TradeNo, Currency: "CNY", AmountMinor: amount, State: "PAID"}, nil
}
func (a *Afdian) queryTrade(ctx context.Context, o Order, trade string) (Receipt, error) {
	if !validAtom(trade, 12, 64) {
		return Receipt{}, ErrInvalid
	}
	rows, err := a.query(ctx, map[string]string{"out_trade_no": trade})
	if err != nil {
		return Receipt{}, err
	}
	if len(rows) != 1 || rows[0].TradeNo != trade {
		return Receipt{}, ErrConflict
	}
	return a.verified(o, rows[0])
}
func (a *Afdian) Query(ctx context.Context, o Order) (Receipt, error) {
	ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	if o.TradeID != "" {
		return a.queryTrade(ctx, o, o.TradeID)
	}
	// Callback loss recovery is bounded. Older orders can use the exact-order
	// reconciliation endpoint, still bound to the original private purchase token.
	for page := 1; page <= 3; page++ {
		rows, err := a.query(ctx, map[string]int{"page": page})
		if err != nil {
			return Receipt{}, err
		}
		for _, row := range rows {
			if row.CustomID == o.ID {
				return a.verified(o, row)
			}
		}
		if len(rows) < 50 {
			break
		}
	}
	return Receipt{OrderID: o.ID, Currency: "CNY", AmountMinor: o.AmountMinor, State: "PENDING"}, nil
}
func (s *Service) RefreshAfdian(ctx context.Context, id, token, trade string) (OrderView, error) {
	s.mu.Lock()
	o, ok := s.state.Orders[id]
	p, isAfdian := s.providers["afdian"].(*Afdian)
	enabled := s.key != nil && !s.damaged
	s.mu.Unlock()
	if !ok || !authorized(o, token) {
		return OrderView{}, ErrNotFound
	}
	if !enabled || !isAfdian || o.Channel != "afdian" {
		return OrderView{}, ErrClosed
	}
	receipt, err := p.queryTrade(ctx, o, trade)
	if err != nil {
		return OrderView{}, err
	}
	if err = s.settle("afdian", receipt); err != nil {
		return OrderView{}, err
	}
	return s.Get(id, token)
}
func (s *Service) afdianNotify(ctx context.Context, p *Afdian, body []byte) error {
	var event struct {
		EC   int `json:"ec"`
		Data struct {
			Type  string      `json:"type"`
			Order afdianOrder `json:"order"`
		} `json:"data"`
	}
	if json.Unmarshal(body, &event) != nil || event.EC != 200 || event.Data.Type != "order" || !validAtom(event.Data.Order.CustomID, 32, 32) {
		return ErrInvalid
	}
	s.mu.Lock()
	o, ok := s.state.Orders[event.Data.Order.CustomID]
	s.mu.Unlock()
	if !ok || o.Channel != "afdian" {
		return ErrNotFound
	}
	// A notification is only a hint. Never trust its status, amount, SKU or
	// custom_order_id without re-querying the creator's authenticated API.
	receipt, err := p.queryTrade(ctx, o, event.Data.Order.TradeNo)
	if err != nil {
		return err
	}
	return s.settle("afdian", receipt)
}

// Record only AFTER the operator verifies an actual refund in the platform.
// The published API documents only successful-order notifications. Do not
// invent a refund status or mistake a failed query for a revoked purchase.
// This method has no public HTTP endpoint and never executes a money transfer.
func (s *Service) RecordAfdianRefund(id, reference string, amount int64) error {
	if !validAtom(reference, 1, 80) {
		return ErrInvalid
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	o, ok := s.state.Orders[id]
	if !ok || o.Channel != "afdian" || o.TradeID == "" || s.key == nil || s.damaged {
		return ErrNotFound
	}
	return s.refundLocked(o, Receipt{OrderID: id, TradeID: o.TradeID, Currency: o.Currency, AmountMinor: o.AmountMinor, State: "REFUND", RefundID: "afdian-reviewed-" + reference, RefundMinor: amount})
}
