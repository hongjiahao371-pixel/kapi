package commerce

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"kapi/sync/internal/pro"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestTermRenewalRefundAndRestartAreDerivedOncePerPaidOrder(t *testing.T) {
	s, pub := service(t, &providerNamedStub{name: "wechat"})
	s.allowLegacySales = false
	now := time.Date(2026, 11, 2, 0, 0, 0, 0, time.UTC).Unix()
	s.now = func() time.Time { return time.Unix(now, 0) }
	offers := pro.ProductCatalogue().Pricing.Offers
	buy := func(product, request, trade string) OrderView {
		t.Helper()
		o, e := s.Create(context.Background(), owner, request, product, "android", "wechat")
		if e != nil {
			t.Fatal(e)
		}
		if e = s.settle("wechat", Receipt{OrderID: o.ID, TradeID: trade, Currency: "CNY", AmountMinor: o.AmountMinor, State: "PAID"}); e != nil {
			t.Fatal(e)
		}
		return o
	}
	if _, e := s.Create(context.Background(), owner, "legacy-order-00001", pro.ProductID, "android", "wechat"); e != ErrClosed {
		t.Fatal("legacy price still on sale", e)
	}
	month := buy(offers[0].ID, "monthly-order-0001", "term-month-tx")
	start := now
	g := s.grantLocked(ownerHash(owner))
	if g.Permanent || g.Until != start+31*86400 {
		t.Fatal("month incorrectly permanent", g)
	}
	now += 5 * 86400
	year := buy(offers[1].ID, "yearly-order-00001", "term-year-tx")
	g = s.grantLocked(ownerHash(owner))
	if g.Until != start+396*86400 {
		t.Fatal("advance renewal lost remaining days", g)
	}
	revision := g.Revision
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if e := s.settle("wechat", Receipt{OrderID: year.ID, TradeID: "term-year-tx", Currency: "CNY", AmountMinor: 6900, State: "PAID"}); e != nil {
				t.Error(e)
			}
		}()
	}
	wg.Wait()
	if got := s.grantLocked(ownerHash(owner)); got != g {
		t.Fatal("repeated settlement added days", got, g)
	}
	if _, e := s.VerifiedOrder(year.ID, strings.Repeat("x", 48), strings.Repeat("a", 32)); e != ErrNotFound {
		t.Fatal("foreign owner read")
	}
	v, e := s.VerifiedOrder(year.ID, owner, strings.Repeat("a", 32))
	if e != nil {
		t.Fatal(e)
	}
	parts := strings.Split(v.EntitlementProof, ".")
	if parts[0] != "GZO2" {
		t.Fatal("term uses permanent legacy protocol")
	}
	b, _ := base64.RawURLEncoding.DecodeString(parts[1])
	var body map[string]any
	json.Unmarshal(b, &body)
	if body["valid_until"] != float64(g.Until) || body["entitlement_revision"] != float64(revision) || body["permanent"] != false {
		t.Fatal("incorrect signed aggregate")
	}
	dir := strings.TrimSuffix(s.file, "/orders.json")
	key := s.key
	s.Close()
	reopened, e := New(Config{Dir: dir, Enabled: true, SigningKey: key, ExpectedPublicKey: pub, Providers: []Provider{&providerNamedStub{name: "wechat"}}})
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	s = reopened
	s.now = func() time.Time { return time.Unix(now, 0) }
	if got := s.grantLocked(ownerHash(owner)); got != g {
		t.Fatal("restart changed expiration", got, g)
	}
	refund := Receipt{OrderID: month.ID, TradeID: "term-month-tx", Currency: "CNY", AmountMinor: 690, State: "REFUND", RefundID: "refund-month", RefundMinor: 690}
	if e = s.settle("wechat", refund); e != nil {
		t.Fatal(e)
	}
	g = s.grantLocked(ownerHash(owner))
	if g.Until != now+365*86400 || g.Revision <= revision {
		t.Fatal("refund removed other purchase or failed revocation", g)
	}
	if e = s.settle("wechat", refund); e != nil {
		t.Fatal(e)
	}
	if s.grantLocked(ownerHash(owner)) != g {
		t.Fatal("duplicate refund changed rights")
	}
	refund = Receipt{OrderID: year.ID, TradeID: "term-year-tx", Currency: "CNY", AmountMinor: 6900, State: "REFUND", RefundID: "refund-year", RefundMinor: 6900}
	if e = s.settle("wechat", refund); e != nil {
		t.Fatal(e)
	}
	if g = s.grantLocked(ownerHash(owner)); g.Permanent || g.Until != 0 {
		t.Fatal("refund left active grant", g)
	}
	now += 400 * 86400
	month = buy(offers[0].ID, "expired-renew-00001", "after-expiry-tx")
	g = s.grantLocked(ownerHash(owner))
	if g.Until != now+31*86400 {
		t.Fatal("expired renewal used old deadline", g)
	}
	life := buy(offers[2].ID, "lifetime-order-001", "term-life-tx")
	if !s.grantLocked(ownerHash(owner)).Permanent {
		t.Fatal("lifetime not permanent")
	}
	if _, e = s.Create(context.Background(), owner, "unneeded-month-001", offers[0].ID, "android", "wechat"); e != ErrConflict {
		t.Fatal("sold duplicate to permanent member", e)
	}
	if e = s.settle("wechat", Receipt{OrderID: life.ID, TradeID: "term-life-tx", Currency: "CNY", AmountMinor: 9900, State: "REFUND", RefundID: "refund-life", RefundMinor: 9900}); e != nil {
		t.Fatal(e)
	}
	g = s.grantLocked(ownerHash(owner))
	if g.Permanent || g.Until != now+31*86400 {
		t.Fatal("lifetime refund destroyed remaining month", g)
	}
}

func TestOnlyOneUnresolvedTermCheckoutPerPurchaseIdentity(t *testing.T) {
	s, _ := service(t, &providerNamedStub{name: "wechat"})
	p := pro.ProductCatalogue().Pricing.Offers
	a, e := s.Create(context.Background(), owner, "unpaid-term-000001", p[0].ID, "android", "wechat")
	if e != nil {
		t.Fatal(e)
	}
	b, e := s.Create(context.Background(), owner, "unpaid-term-000001", p[0].ID, "android", "wechat")
	if e != nil || a.ID != b.ID {
		t.Fatal("create retry duplicated")
	}
	if _, e = s.Create(context.Background(), owner, "other-term-000001", p[1].ID, "android", "wechat"); e != ErrPendingOrder {
		t.Fatal("second checkout before reconciliation", e)
	}
	if e = s.settle("wechat", Receipt{OrderID: a.ID, Currency: "CNY", AmountMinor: 690, State: "CLOSED"}); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Create(context.Background(), owner, "other-term-000001", p[1].ID, "android", "wechat"); e != nil {
		t.Fatal("closed checkout blocked renewal", e)
	}
}

func TestAcceptanceBuyerRestrictionDoesNotRestrictExistingOrderRecovery(t *testing.T) {
	s, _ := service(t, &providerNamedStub{name: "wechat"})
	s.acceptanceOwnerHash = ownerHash(owner)
	offer := pro.ProductCatalogue().Pricing.Offers[0]
	if _, e := s.Create(context.Background(), strings.Repeat("z", 48), "foreign-buyer-0001", offer.ID, "android", "wechat"); e != ErrClosed {
		t.Fatal("non-acceptance buyer can pay", e)
	}
	o, e := s.Create(context.Background(), owner, "allowed-buyer-0001", offer.ID, "android", "wechat")
	if e != nil {
		t.Fatal(e)
	}
	s.acceptanceOwnerHash = ownerHash(strings.Repeat("z", 48))
	if _, e = s.Get(o.ID, owner); e != nil {
		t.Fatal("sales restriction blocked existing purchase recovery", e)
	}
}

func TestPublicMonthlySaleRestrictsNewOrdersAndKeepsRecovery(t *testing.T) {
	s, _ := service(t, &providerNamedStub{name: "afdian"}, &providerNamedStub{name: "wechat"})
	offers := pro.ProductCatalogue().Pricing.Offers
	s.publicOffers = []string{offers[0].ID}
	h := NewHandler(s)
	var catalogue struct {
		Enabled  bool     `json:"payments_enabled"`
		Offers   []string `json:"available_offer_ids"`
		Channels []string `json:"channels"`
	}
	checkCatalog := func(enabled bool) {
		t.Helper()
		r := call(h, http.MethodGet, "/v1/commerce/catalog", "", "", "")
		if r.Code != http.StatusOK || json.Unmarshal(r.Body.Bytes(), &catalogue) != nil || catalogue.Enabled != enabled {
			t.Fatal("unexpected catalogue", r.Code, catalogue)
		}
		if enabled {
			if len(catalogue.Offers) != 1 || catalogue.Offers[0] != offers[0].ID || len(catalogue.Channels) != 1 || catalogue.Channels[0] != "afdian" {
				t.Fatal("unexpected offer or channel", catalogue)
			}
		} else if len(catalogue.Offers) != 0 || len(catalogue.Channels) != 0 {
			t.Fatal("closed catalogue still offered purchase", catalogue)
		}
	}
	checkCatalog(true)
	for _, id := range []string{offers[1].ID, offers[2].ID, pro.ProductID} {
		if _, err := s.Create(context.Background(), owner, "closed-offer-0001", id, "android", "afdian"); err != ErrClosed {
			t.Fatal("closed offer can be purchased", id, err)
		}
	}
	if _, err := s.Create(context.Background(), owner, "closed-channel-001", offers[0].ID, "android", "wechat"); err != ErrClosed {
		t.Fatal("alternate channel can create public order", err)
	}
	ctx := context.Background()
	o, err := s.Create(ctx, owner, "monthly-release-001", offers[0].ID, "android", "afdian")
	if err != nil || o.State != "CREATED" || o.AmountMinor != 690 {
		t.Fatal("monthly offer unavailable", o.State, err)
	}
	retry, err := s.Create(ctx, owner, "monthly-release-001", offers[0].ID, "android", "afdian")
	if err != nil || retry.ID != o.ID || len(s.state.Orders) != 1 {
		t.Fatal("retry did not preserve the order", err)
	}
	if err := s.settle("afdian", Receipt{OrderID: o.ID, TradeID: "monthly-release-tx", Currency: "CNY", AmountMinor: 690, State: "PAID"}); err != nil {
		t.Fatal(err)
	}
	s.enabled = false
	checkCatalog(false)
	recovered, err := s.VerifiedOrder(o.ID, owner, strings.Repeat("a", 32))
	if err != nil || recovered.State != "PAID" || recovered.EntitlementProof == "" {
		t.Fatal("closing public sale prevented recovery", err)
	}
}
