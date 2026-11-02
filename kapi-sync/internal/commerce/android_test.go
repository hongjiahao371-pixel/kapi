package commerce

import (
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"kapi/sync/internal/pro"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

func androidOrder(t *testing.T, s *Service) OrderView {
	t.Helper()
	o, e := s.Create(context.Background(), owner, "android-request-0001", pro.ProductID, "android", "wechat")
	if e != nil {
		t.Fatal(e)
	}
	return o
}
func decodeProof(t *testing.T, v OrderView, pub string, nonce string) map[string]any {
	t.Helper()
	parts := strings.Split(v.EntitlementProof, ".")
	if len(parts) != 3 || parts[0] != "GZO1" {
		t.Fatal("missing signed receipt")
	}
	body, _ := base64.RawURLEncoding.DecodeString(parts[1])
	sig, _ := base64.RawURLEncoding.DecodeString(parts[2])
	der, _ := base64.StdEncoding.DecodeString(pub)
	parsed, _ := x509.ParsePKIXPublicKey(der)
	hash := sha256.Sum256(body)
	if !ecdsa.VerifyASN1(parsed.(*ecdsa.PublicKey), hash[:], sig) {
		t.Fatal("signature")
	}
	var payload map[string]any
	if json.Unmarshal(body, &payload) != nil || payload["nonce"] != nonce || payload["id"] != v.ID || payload["state"] != v.State {
		t.Fatal("binding")
	}
	return payload
}
func TestAndroidSignedReceiptAndRefundRecoveryNeverExposeAnOfflineUnlockCode(t *testing.T) {
	s, pub := service(t, &providerNamedStub{name: "wechat"})
	o := androidOrder(t, s)
	paid := Receipt{OrderID: o.ID, TradeID: "android-tx", Currency: "CNY", AmountMinor: 2990, State: "PAID"}
	if e := s.settle("wechat", paid); e != nil {
		t.Fatal(e)
	}
	nonce := strings.Repeat("a", 32)
	v, e := s.VerifiedOrder(o.ID, owner, nonce)
	if e != nil || v.License != "" {
		t.Fatal("Android must use a revocable order receipt", e)
	}
	body := decodeProof(t, v, pub, nonce)
	if body["revision"].(float64) != 2 {
		t.Fatal(body)
	}
	refund := paid
	refund.State = "REFUND"
	refund.RefundID = "refund-1"
	refund.RefundMinor = 1000
	for i := 0; i < 4; i++ {
		if e = s.settle("wechat", refund); e != nil {
			t.Fatal(e)
		}
	}
	partial, _ := s.VerifiedOrder(o.ID, owner, strings.Repeat("b", 32))
	if partial.State != "PAID" {
		t.Fatal("partial refund must not revoke whole product")
	}
	refund.RefundID = "refund-2"
	refund.RefundMinor = 1990
	var wg sync.WaitGroup
	for i := 0; i < 25; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if e := s.settle("wechat", refund); e != nil {
				t.Error(e)
			}
		}()
	}
	wg.Wait()
	after, _ := s.VerifiedOrder(o.ID, owner, strings.Repeat("c", 32))
	b := decodeProof(t, after, pub, strings.Repeat("c", 32))
	if after.State != "REFUNDED" || b["revision"].(float64) != 4 || len(s.state.Orders[o.ID].Refunds) != 2 {
		t.Fatal("double counted refund")
	}
	if s.settle("wechat", paid) == nil {
		t.Fatal("old paid notification restored a refunded purchase")
	}
	invalid := refund
	invalid.RefundMinor = 1
	if s.settle("wechat", invalid) == nil {
		t.Fatal("same refund id changed value")
	}
	if _, e = s.VerifiedOrder(o.ID, strings.Repeat("x", 48), nonce); e != ErrNotFound {
		t.Fatal("owner isolation")
	}
	if _, e = s.VerifiedOrder(o.ID, owner, "invalid"); e == nil {
		t.Fatal("invalid challenge")
	}
	dir := s.file[:len(s.file)-len("orders.json")]
	s.Close()
	reloaded, e := New(Config{Dir: dir, Enabled: true, SigningKey: s.key, ExpectedPublicKey: pub, Providers: []Provider{&providerNamedStub{name: "wechat"}}})
	if e != nil {
		t.Fatal(e)
	}
	defer reloaded.Close()
	restored, _ := reloaded.VerifiedOrder(o.ID, owner, nonce)
	if restored.State != "REFUNDED" {
		t.Fatal("refund lost on restart")
	}
}
func TestRefundBeforePaymentAndPlatformReleaseBoundaries(t *testing.T) {
	s, _ := service(t, &providerNamedStub{name: "wechat"})
	o := androidOrder(t, s)
	r := Receipt{OrderID: o.ID, TradeID: "tx-first-refund", Currency: "CNY", AmountMinor: 2990, State: "REFUND", RefundID: "out-of-order-refund", RefundMinor: 2990}
	if e := s.settle("wechat", r); e != nil {
		t.Fatal(e)
	}
	r.State = "PAID"
	if s.settle("wechat", r) == nil {
		t.Fatal("late payment resurrected full refund")
	}
	key, pub := issuer(t)
	first, e := New(Config{Dir: t.TempDir(), Enabled: true, SigningKey: key, ExpectedPublicKey: pub, Providers: []Provider{&providerNamedStub{name: "wechat"}}})
	if e != nil {
		t.Fatal(e)
	}
	defer first.Close()
	for _, platform := range []string{"nas", "ios", "harmony"} {
		if _, e = first.Create(context.Background(), owner, "different-request", pro.ProductID, platform, "wechat"); e == nil {
			t.Fatal("first release must stay Android-only", platform)
		}
	}
}
func TestAndroidAlipayAndWechatAppCheckoutUseProviderSignedArguments(t *testing.T) {
	key := merchant(t)
	a := ali(t, key)
	o := Order{ID: strings.Repeat("a", 32), Platform: "android", AmountMinor: 2990, Currency: "CNY", ExpiresAt: time.Now().Unix() + 1800}
	checkout, e := a.Prepare(context.Background(), o)
	v, _ := url.ParseQuery(checkout.Value)
	if e != nil || checkout.Kind != "alipay_app" || v.Get("method") != "alipay.trade.app.pay" || verifyRSA(&key.PublicKey, canonical(v, false), v.Get("sign")) != nil {
		t.Fatal("APP Alipay signature", e)
	}
	var biz map[string]string
	_ = json.Unmarshal([]byte(v.Get("biz_content")), &biz)
	if biz["product_code"] != "QUICK_MSECURITY_PAY" || biz["total_amount"] != "29.90" {
		t.Fatal(biz)
	}
	p := wx(t, key)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v3/pay/transactions/app" {
			t.Error("wrong API", r.URL.Path)
		}
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), `"total":2990`) {
			t.Error("wrong price")
		}
		data := []byte(`{"prepay_id":"wxprepay_fixture_0001"}`)
		at := fmt.Sprint(time.Now().Unix())
		sig, _ := signRSA(key, []byte(at+"\nnonce00000001\n"+string(data)+"\n"))
		w.Header().Set("Wechatpay-Timestamp", at)
		w.Header().Set("Wechatpay-Nonce", "nonce00000001")
		w.Header().Set("Wechatpay-Serial", p.cfg.PlatformSerial)
		w.Header().Set("Wechatpay-Signature", sig)
		w.Write(data)
	}))
	defer server.Close()
	p.endpoint = server.URL
	p.client = server.Client()
	checkout, e = p.Prepare(context.Background(), o)
	var args map[string]string
	_ = json.Unmarshal([]byte(checkout.Value), &args)
	if e != nil || checkout.Kind != "wechat_app" || args["package"] != "Sign=WXPay" || verifyRSA(&key.PublicKey, []byte(args["appid"]+"\n"+args["timestamp"]+"\n"+args["noncestr"]+"\n"+args["prepayid"]+"\n"), args["sign"]) != nil {
		t.Fatal("APP Wechat signature", e)
	}
}
func TestWechatRefundNotificationAuthenticatesFullAndPartialAmounts(t *testing.T) {
	key := merchant(t)
	p := wx(t, key)
	s, _ := service(t, &providerNamedStub{name: "wechat"})
	o := androidOrder(t, s)
	s.providers["wechat"] = p
	paid := Receipt{OrderID: o.ID, TradeID: "tx-refund-notify", Currency: "CNY", AmountMinor: 2990, State: "PAID"}
	if e := s.settle("wechat", paid); e != nil {
		t.Fatal(e)
	}
	data := []byte(fmt.Sprintf(`{"mchid":%q,"out_trade_no":%q,"transaction_id":"tx-refund-notify","refund_id":"wx-refund-1","refund_status":"SUCCESS","amount":{"total":2990,"refund":2990}}`, p.cfg.MerchantID, o.ID))
	// Reuse the official encrypted envelope helper, then resign the refund event.
	header, body := encryptedWx(t, p, key, data, 0)
	var env map[string]any
	_ = json.Unmarshal(body, &env)
	env["event_type"] = "REFUND.SUCCESS"
	body, _ = json.Marshal(env)
	sig, _ := signRSA(key, []byte(header.Get("Wechatpay-Timestamp")+"\n"+header.Get("Wechatpay-Nonce")+"\n"+string(body)+"\n"))
	header.Set("Wechatpay-Signature", sig)
	r := httptest.NewRequest("POST", "/v1/commerce/notify/wechat", strings.NewReader(string(body)))
	r.Header = header
	w := httptest.NewRecorder()
	NewHandler(s).ServeHTTP(w, r)
	if w.Code != 200 || s.state.Orders[o.ID].State != "REFUNDED" {
		t.Fatal(w.Code, "valid refund rejected")
	}
}

func TestPausingNewSalesStillAcceptsExistingPurchaseAndRefundProofs(t *testing.T) {
	s, pub := service(t, &providerNamedStub{name: "wechat"})
	o := androidOrder(t, s)
	paid := Receipt{OrderID: o.ID, TradeID: "paused-sales-tx", Currency: "CNY", AmountMinor: 2990, State: "PAID"}
	s.enabled = false
	if _, e := s.Create(context.Background(), owner, "new-order-while-paused", pro.ProductID, "android", "wechat"); e != ErrClosed {
		t.Fatal("paused service sold another order")
	}
	if e := s.settle("wechat", paid); e != nil {
		t.Fatal("existing payment was discarded", e)
	}
	paid.State = "REFUND"
	paid.RefundID = "paused-sales-refund"
	paid.RefundMinor = 2990
	if e := s.settle("wechat", paid); e != nil {
		t.Fatal("existing refund discarded", e)
	}
	v, e := s.VerifiedOrder(o.ID, owner, strings.Repeat("d", 32))
	if e != nil || v.State != "REFUNDED" {
		t.Fatal(e)
	}
	decodeProof(t, v, pub, strings.Repeat("d", 32))
}

func TestPreparedOrderTimeoutCannotInventAClosedPaymentAndPermitDoublePurchase(t *testing.T) {
	s, _ := service(t, &providerNamedStub{name: "wechat"})
	o := androidOrder(t, s)
	s.now = func() time.Time { return time.Unix(o.ExpiresAt+1, 0) }
	pending := Receipt{OrderID: o.ID, Currency: "CNY", AmountMinor: 2990, State: "PENDING"}
	if e := s.settle("wechat", pending); e != nil {
		t.Fatal(e)
	}
	if s.state.Orders[o.ID].State != "CREATED" {
		t.Fatal("clock expiry must not decide merchant payment state")
	}
	if _, e := s.Create(context.Background(), owner, "android-request-0001", pro.ProductID, "android", "wechat"); e != ErrPendingOrder {
		t.Fatal("expired cashier was reopened", e)
	}
	pending.State = "CLOSED"
	if e := s.settle("wechat", pending); e != nil {
		t.Fatal("verified unpaid close needs no completed trade id", e)
	}
	if s.state.Orders[o.ID].State != "CLOSED" {
		t.Fatal("provider close not retained")
	}
}
