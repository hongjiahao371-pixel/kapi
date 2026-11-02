package commerce

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"kapi/sync/internal/pro"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
)

func afdianFixture(t *testing.T) (*Afdian, *Service, OrderView, *[]afdianOrder) {
	t.Helper()
	p, e := NewAfdian(AfdianConfig{UserID: strings.Repeat("a", 32), Token: strings.Repeat("t", 40), PlanID: strings.Repeat("b", 32), SKUID: strings.Repeat("c", 32), ProductType: 1})
	if e != nil {
		t.Fatal(e)
	}
	rows := []afdianOrder{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("User-Agent") != "GuizhangCommerce/1.0" || r.Header.Get("Accept") != "application/json" {
			t.Error("API client identification missing")
			w.WriteHeader(403)
			return
		}
		var body struct {
			User   string `json:"user_id"`
			Params string `json:"params"`
			TS     int64  `json:"ts"`
			Sign   string `json:"sign"`
		}
		if r.Method != "POST" || json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Error("bad request")
			w.WriteHeader(400)
			return
		}
		expected := md5.Sum([]byte(p.cfg.Token + "params" + body.Params + "ts" + strconv.FormatInt(body.TS, 10) + "user_id" + p.cfg.UserID))
		if body.User != p.cfg.UserID || body.Sign != hex.EncodeToString(expected[:]) {
			t.Error("unsigned API request")
			w.WriteHeader(401)
			return
		}
		var params map[string]any
		if json.Unmarshal([]byte(body.Params), &params) != nil {
			t.Error("invalid params")
		}
		selected := rows
		if trade, ok := params["out_trade_no"].(string); ok {
			selected = nil
			for _, row := range rows {
				if row.TradeNo == trade {
					selected = append(selected, row)
				}
			}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"ec": 200, "data": map[string]any{"list": selected}})
	}))
	t.Cleanup(server.Close)
	p.apiURL = server.URL
	p.client = server.Client()
	s, _ := service(t, p)
	o, e := s.Create(context.Background(), owner, "afdian-request-00001", pro.ProductID, "android", "afdian")
	if e != nil {
		t.Fatal(e)
	}
	return p, s, o, &rows
}
func jsonNumber(n int64) string { b, _ := json.Marshal(n); return string(b) }
func paidAfdian(p *Afdian, id string) afdianOrder {
	row := afdianOrder{TradeNo: "20261102000001234", CustomID: id, PlanID: p.cfg.PlanID, Total: "29.90", Status: 2, ProductType: 1}
	row.SKU = append(row.SKU, struct {
		ID    string `json:"sku_id"`
		Count int    `json:"count"`
	}{p.cfg.SKUID, 1})
	return row
}
func TestAfdianOfficialCheckoutAndQueryBinding(t *testing.T) {
	p, s, o, rows := afdianFixture(t)
	u, e := url.Parse(o.Checkout.Value)
	if e != nil || u.Scheme != "https" || u.Host != "afdian.com" || u.Path != "/order/create" || u.Query().Get("custom_order_id") != o.ID || u.Query().Get("plan_id") != p.cfg.PlanID {
		t.Fatal("incorrect official checkout", e)
	}
	pending, e := s.Refresh(context.Background(), o.ID, owner)
	if e != nil || pending.State != "CREATED" {
		t.Fatal("unpaid grant", pending, e)
	}
	*rows = []afdianOrder{paidAfdian(p, o.ID)}
	paid, e := s.Refresh(context.Background(), o.ID, owner)
	if e != nil || paid.State != "PAID" || paid.License != "" {
		t.Fatal("paid not verified or transferable license issued", paid, e)
	}
	proof, e := s.VerifiedOrder(o.ID, owner, strings.Repeat("d", 32))
	if e != nil || !strings.HasPrefix(proof.EntitlementProof, "GZO1.") {
		t.Fatal("no signed proof", e)
	}
}
func TestAfdianRejectsWrongAmountPlanProductQuantityAndOrder(t *testing.T) {
	for _, test := range []struct {
		name   string
		change func(*afdianOrder)
	}{
		{"wrong-price", func(r *afdianOrder) { r.Total = "0.01" }}, {"wrong-plan", func(r *afdianOrder) { r.PlanID = strings.Repeat("e", 32) }},
		{"wrong-order", func(r *afdianOrder) { r.CustomID = strings.Repeat("e", 32) }}, {"coupon", func(r *afdianOrder) { r.RedeemID = "free-code" }},
		{"wrong-sku", func(r *afdianOrder) { r.SKU[0].ID = strings.Repeat("e", 32) }}, {"quantity", func(r *afdianOrder) { r.SKU[0].Count = 2 }},
		{"unpaid", func(r *afdianOrder) { r.Status = 0 }}, {"wrong-product", func(r *afdianOrder) { r.ProductType = 0 }},
	} {
		t.Run(test.name, func(t *testing.T) {
			p, s, o, rows := afdianFixture(t)
			r := paidAfdian(p, o.ID)
			test.change(&r)
			*rows = []afdianOrder{r}
			_, e := s.RefreshAfdian(context.Background(), o.ID, owner, r.TradeNo)
			if e == nil {
				t.Fatal("unverified purchase accepted")
			}
			got, _ := s.Get(o.ID, owner)
			if got.State != "CREATED" {
				t.Fatal("rights mutated")
			}
		})
	}
}
func TestAfdianNotificationsAreHintsRepeatedCallbacksAndRefundAreIdempotent(t *testing.T) {
	p, s, o, rows := afdianFixture(t)
	row := paidAfdian(p, o.ID)
	body, _ := json.Marshal(map[string]any{"ec": 200, "data": map[string]any{"type": "order", "order": row}})
	if e := s.afdianNotify(context.Background(), p, body); e == nil {
		t.Fatal("forged callback granted without API record")
	}
	*rows = []afdianOrder{row}
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if e := s.afdianNotify(context.Background(), p, body); e != nil {
				t.Error(e)
			}
		}()
	}
	wg.Wait()
	before, _ := os.ReadFile(s.file)
	if e := s.afdianNotify(context.Background(), p, body); e != nil {
		t.Fatal(e)
	}
	after, _ := os.ReadFile(s.file)
	if string(before) != string(after) {
		t.Fatal("repeated callback reissued rights")
	}
	if e := s.RecordAfdianRefund(o.ID, "platform-refund-01", 2990); e != nil {
		t.Fatal(e)
	}
	if e := s.RecordAfdianRefund(o.ID, "platform-refund-01", 2990); e != nil {
		t.Fatal("duplicate refund", e)
	}
	if e := s.afdianNotify(context.Background(), p, body); e != ErrConflict {
		t.Fatal("late notification must not revive refunded order", e)
	}
	got, _ := s.Get(o.ID, owner)
	if got.State != "REFUNDED" {
		t.Fatal("late paid callback revived refunded rights")
	}
}
func TestAfdianSpecificReconciliationRequiresPrivateOwnerAndCannotAttachOthersTrade(t *testing.T) {
	p, s, o, rows := afdianFixture(t)
	*rows = []afdianOrder{paidAfdian(p, strings.Repeat("e", 32))}
	if _, e := s.RefreshAfdian(context.Background(), o.ID, strings.Repeat("x", 48), (*rows)[0].TradeNo); e != ErrNotFound {
		t.Fatal("other owner read")
	}
	h := NewHandler(s)
	w := call(h, "POST", "/v1/commerce/orders/"+o.ID+"/afdian-query", owner, `{"trade_no":"20261102000001234"}`, "application/json")
	if w.Code != 409 {
		t.Fatal("attached other order", w.Code)
	}
	(*rows)[0] = paidAfdian(p, o.ID)
	w = call(h, "POST", "/v1/commerce/orders/"+o.ID+"/afdian-query?receipt_nonce="+strings.Repeat("d", 32), owner, `{"trade_no":"20261102000001234"}`, "application/json")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"entitlement_proof":"GZO1.`) {
		t.Fatal(w.Code, w.Body.String())
	}
	if w = call(h, "POST", "/v1/commerce/orders/"+o.ID+"/refund", owner, `{}`, "application/json"); w.Code != 405 {
		t.Fatal("public refund action exposed")
	}
}
func TestAfdianConfigurationAndUnsupportedPlatformsFailClosed(t *testing.T) {
	if _, e := NewAfdian(AfdianConfig{}); e == nil {
		t.Fatal("empty config")
	}
	p, _, _, _ := afdianFixture(t)
	for _, platform := range []string{"ios", "harmony", "nas"} {
		if _, e := p.Prepare(context.Background(), Order{ID: strings.Repeat("a", 32), Platform: platform, AmountMinor: 2990, Currency: "CNY"}); e == nil {
			t.Fatal("unsupported purchase platform", platform)
		}
	}
}

func TestAfdianConnectionCheckIsReadOnlyAndRejectsAuthenticationFailure(t *testing.T) {
	p, s, o, rows := afdianFixture(t)
	*rows = []afdianOrder{paidAfdian(p, o.ID)}
	before, err := os.ReadFile(s.file)
	if err != nil {
		t.Fatal(err)
	}
	count, err := p.CheckConnection(context.Background())
	if err != nil || count != 1 {
		t.Fatal("authenticated query failed", count, err)
	}
	after, err := os.ReadFile(s.file)
	if err != nil || string(before) != string(after) {
		t.Fatal("read-only check changed order store", err)
	}
	got, _ := s.Get(o.ID, owner)
	if got.State != "CREATED" {
		t.Fatal("connection check granted rights")
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"ec":400005,"em":"sensitive diagnostic must not escape","data":{"list":[]}}`))
	}))
	defer server.Close()
	p.apiURL, p.client = server.URL, server.Client()
	if count, err := p.CheckConnection(context.Background()); err != ErrInvalid || count != 0 {
		t.Fatal("failed authentication accepted", count, err)
	}
}
