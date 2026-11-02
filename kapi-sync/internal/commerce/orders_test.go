package commerce

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"kapi/sync/internal/pro"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type providerStub struct{ receipt Receipt }

func (p *providerStub) Name() string { return "fixture" }
func (p *providerStub) Prepare(context.Context, Order) (Checkout, error) {
	return Checkout{Kind: "redirect", Value: "https://example.invalid/checkout"}, nil
}
func (p *providerStub) Query(context.Context, Order) (Receipt, error) { return p.receipt, nil }
func issuer(t *testing.T) (*ecdsa.PrivateKey, string) {
	t.Helper()
	key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	return key, base64.StdEncoding.EncodeToString(der)
}
func merchant(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	return key
}
func service(t *testing.T, providers ...Provider) (*Service, string) {
	t.Helper()
	key, pub := issuer(t)
	s, e := New(Config{Dir: t.TempDir(), Enabled: true, SigningKey: key, ExpectedPublicKey: pub, AllowNasPurchases: true, Providers: providers})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	return s, pub
}

const owner = "fixture_owner_0123456789012345678901234567890123456789"

func create(t *testing.T, s *Service, channel, request string) OrderView {
	t.Helper()
	o, e := s.Create(context.Background(), owner, request, pro.ProductID, "nas", channel)
	if e != nil {
		t.Fatal(e)
	}
	return o
}
func call(h http.Handler, method, path, token, body, contentType string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+token)
	if contentType != "" {
		r.Header.Set("Content-Type", contentType)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestDisabledCheckoutCannotGrantAndCatalogHasExactPrice(t *testing.T) {
	s, e := New(Config{Dir: t.TempDir()})
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	h := NewHandler(s)
	w := call(h, "GET", "/v1/commerce/catalog", "", "", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"amount_minor":2990`) || !strings.Contains(w.Body.String(), `"payments_enabled":false`) {
		t.Fatal(w.Body.String())
	}
	body := `{"product_id":"guizhang-pro-lifetime","platform":"android","channel":"alipay","request_id":"request-00000001"}`
	if w = call(h, "POST", "/v1/commerce/orders", owner, body, "application/json"); w.Code != 503 {
		t.Fatal(w.Code)
	}
	if len(s.state.Orders) != 0 {
		t.Fatal("closed checkout created an order")
	}
	if w = call(h, "POST", "/v1/commerce/orders", owner, strings.TrimSuffix(body, "}")+`,"amount_minor":1}`, "application/json"); w.Code != 400 {
		t.Fatal("client price accepted", w.Code)
	}
	for _, platform := range []string{"ios", "harmony"} {
		if _, e = s.Create(context.Background(), owner, "request-00000001", pro.ProductID, platform, "alipay"); e == nil || e.Error() != "store_purchase_required" {
			t.Fatal(platform, e)
		}
	}
}
func TestIdempotentCreateOwnerAuthPendingAndRepeatedActivation(t *testing.T) {
	provider := &providerStub{}
	s, pub := service(t, provider)
	o := create(t, s, "fixture", "request-00000001")
	same := create(t, s, "fixture", "request-00000001")
	if same.ID != o.ID || len(s.state.Orders) != 1 {
		t.Fatal("order duplicated")
	}
	if _, e := s.Get(o.ID, strings.Repeat("x", 48)); e != ErrNotFound {
		t.Fatal("other owner read order", e)
	}
	provider.receipt = Receipt{OrderID: o.ID, Currency: "CNY", AmountMinor: 2990, State: "PENDING"}
	pending, e := s.Refresh(context.Background(), o.ID, owner)
	if e != nil || pending.State != "CREATED" || pending.License != "" {
		t.Fatal("pending order granted", pending, e)
	}
	provider.receipt = Receipt{OrderID: o.ID, TradeID: "provider-trade-1", Currency: "CNY", AmountMinor: 2990, State: "PAID"}
	paid, e := s.Refresh(context.Background(), o.ID, owner)
	if e != nil {
		t.Fatal(e)
	}
	info, e := pro.Verify(paid.License, pub)
	if e != nil || info.ID != "order-"+o.ID {
		t.Fatal("invalid issued entitlement", e)
	}
	encoded, _ := json.Marshal(paid)
	if _, e = pro.OrderActivation(encoded, o.ID, pub); e != nil {
		t.Fatal("NAS receipt could not activate", e)
	}
	before, _ := os.ReadFile(s.file)
	var wg sync.WaitGroup
	for i := 0; i < 25; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if e := s.settle("fixture", provider.receipt); e != nil {
				t.Error(e)
			}
		}()
	}
	wg.Wait()
	after, _ := os.ReadFile(s.file)
	if !bytes.Equal(before, after) {
		t.Fatal("duplicate notification rewrote or reissued entitlement")
	}
	got, _ := s.Get(o.ID, owner)
	if got.License != paid.License {
		t.Fatal("activation changed")
	}
	h := NewHandler(s)
	w := call(h, "GET", "/v1/commerce/orders/"+o.ID, owner, "", "")
	if strings.Contains(w.Body.String(), owner) || strings.Contains(w.Body.String(), "owner_hash") {
		t.Fatal("owner secret leaked")
	}
	for _, path := range []string{"/v1/commerce/orders/" + o.ID + "/paid", "/v1/commerce/activate"} {
		if w = call(h, "POST", path, owner, `{"paid":true}`, "application/json"); w.Code == 200 {
			t.Fatal("client success bypass")
		}
	}
}
func TestRestartLockCorruptionAndWrongIssuer(t *testing.T) {
	provider := &providerStub{}
	s, pub := service(t, provider)
	o := create(t, s, "fixture", "request-00000001")
	receipt := Receipt{OrderID: o.ID, TradeID: "trade1", Currency: "CNY", AmountMinor: 2990, State: "PAID"}
	if e := s.settle("fixture", receipt); e != nil {
		t.Fatal(e)
	}
	dir := filepath.Dir(s.file)
	if second, e := New(Config{Dir: dir}); e == nil {
		second.Close()
		t.Fatal("two writers accepted")
	}
	paid, _ := s.Get(o.ID, owner)
	s.Close()
	reopened, e := New(Config{Dir: dir})
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	restored, e := reopened.Get(o.ID, owner)
	if e != nil || restored.License != paid.License {
		t.Fatal("restart lost receipt", e)
	}
	info, _ := os.Stat(s.file)
	if info.Mode().Perm() != 0600 {
		t.Fatal("private order file permissions")
	}
	key, _ := issuer(t)
	if bad, e := New(Config{Dir: t.TempDir(), Enabled: true, SigningKey: key, ExpectedPublicKey: pub, Providers: []Provider{provider}}); e == nil {
		bad.Close()
		t.Fatal("wrong issuer accepted")
	}
	damaged := t.TempDir()
	file := filepath.Join(damaged, "orders.json")
	_ = os.WriteFile(file, []byte("broken"), 0600)
	if bad, e := New(Config{Dir: damaged}); e == nil {
		bad.Close()
		t.Fatal("damaged data reset")
	}
	b, _ := os.ReadFile(file)
	if string(b) != "broken" {
		t.Fatal("failure evidence erased")
	}
}
func TestAmountCurrencyTradeAndRefundStateConflictsNeverIssue(t *testing.T) {
	s, _ := service(t, &providerStub{})
	o := create(t, s, "fixture", "request-00000001")
	valid := Receipt{OrderID: o.ID, TradeID: "provider-tx", Currency: "CNY", AmountMinor: 2990, State: "PAID"}
	for _, receipt := range []Receipt{{OrderID: o.ID, TradeID: "provider-tx", Currency: "CNY", AmountMinor: 1, State: "PAID"}, {OrderID: o.ID, TradeID: "provider-tx", Currency: "USD", AmountMinor: 2990, State: "PAID"}, {OrderID: o.ID, Currency: "CNY", AmountMinor: 2990, State: "PAID"}} {
		if e := s.settle("fixture", receipt); e == nil {
			t.Fatal("conflict accepted")
		}
	}
	if e := s.settle("other", valid); e == nil {
		t.Fatal("different channel")
	}
	initial, _ := s.Get(o.ID, owner)
	if initial.License != "" {
		t.Fatal("invalid receipt granted")
	}
	if e := s.settle("fixture", valid); e != nil {
		t.Fatal(e)
	}
	next := create(t, s, "fixture", "request-00000002")
	reused := valid
	reused.OrderID = next.ID
	if e := s.settle("fixture", reused); e == nil {
		t.Fatal("provider trade reused for another order")
	}
	closed := valid
	closed.State = "CLOSED"
	if e := s.settle("fixture", closed); e != nil {
		t.Fatal(e)
	}
	refunded, _ := s.Get(o.ID, owner)
	if refunded.State != "REFUNDED" || refunded.License != "" {
		t.Fatal("refunded order still retrieves license")
	}
	if e := s.settle("fixture", valid); e == nil {
		t.Fatal("stale success revived a refunded order")
	}
}

func ali(t *testing.T, key *rsa.PrivateKey) *Alipay {
	t.Helper()
	a, e := NewAlipay(AlipayConfig{AppID: "2026102900000001", SellerID: "2088000000000001", NotifyURL: "https://example.invalid/notify", PrivateKey: key, PublicKey: &key.PublicKey})
	if e != nil {
		t.Fatal(e)
	}
	return a
}
func signedAli(t *testing.T, a *Alipay, key *rsa.PrivateKey, id, trade, amount, status string) url.Values {
	t.Helper()
	v := url.Values{"app_id": {a.cfg.AppID}, "seller_id": {a.cfg.SellerID}, "out_trade_no": {id}, "trade_no": {trade}, "total_amount": {amount}, "trade_status": {status}, "sign_type": {"RSA2"}}
	sign, e := signRSA(key, canonical(v, true))
	if e != nil {
		t.Fatal(e)
	}
	v.Set("sign", sign)
	return v
}
func TestRealAlipayNotificationVerificationAndPersistence(t *testing.T) {
	key := merchant(t)
	a := ali(t, key)
	s, pub := service(t, a)
	o := create(t, s, "alipay", "request-00000001")
	h := NewHandler(s)
	signature := signedAli(t, a, key, o.ID, "alipay-tx-1", "29.90", "TRADE_SUCCESS")
	for _, mutation := range []func(url.Values){func(v url.Values) { v.Set("total_amount", "0.01") }, func(v url.Values) { v.Set("seller_id", "2088000000000999") }, func(v url.Values) { v.Set("app_id", "2026102900000999") }, func(v url.Values) { v.Add("total_amount", "29.90") }} {
		copy := url.Values{}
		for k, v := range signature {
			copy[k] = append([]string{}, v...)
		}
		mutation(copy)
		w := call(h, "POST", "/v1/commerce/notify/alipay", "", copy.Encode(), "application/x-www-form-urlencoded")
		if w.Code != 400 {
			t.Fatal("forged notification", w.Code)
		}
	}
	wrongPrice := signedAli(t, a, key, o.ID, "alipay-tx-1", "0.01", "TRADE_SUCCESS")
	if w := call(h, "POST", "/v1/commerce/notify/alipay", "", wrongPrice.Encode(), "application/x-www-form-urlencoded"); w.Code != 400 {
		t.Fatal("signed wrong amount accepted")
	}
	if got, _ := s.Get(o.ID, owner); got.License != "" {
		t.Fatal("invalid notifications unlocked")
	}
	for i := 0; i < 3; i++ {
		w := call(h, "POST", "/v1/commerce/notify/alipay", "", signature.Encode(), "application/x-www-form-urlencoded")
		if w.Code != 200 || w.Body.String() != "success" {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	got, _ := s.Get(o.ID, owner)
	if _, e := pro.Verify(got.License, pub); e != nil {
		t.Fatal("verified notification did not issue valid code", e)
	}
	checkout, _ := url.Parse(o.Checkout.Value)
	if checkout.Query().Get("method") != "alipay.trade.wap.pay" {
		t.Fatal("wrong checkout method")
	}
	if verifyRSA(&key.PublicKey, canonical(checkout.Query(), false), checkout.Query().Get("sign")) != nil {
		t.Fatal("invalid checkout signature")
	}
}
func wx(t *testing.T, key *rsa.PrivateKey) *Wechat {
	t.Helper()
	p, e := NewWechat(WechatConfig{AppID: "wxfixtureappid0001", MerchantID: "1234567890", MerchantSerial: "merchant0001", PlatformSerial: "platform0001", NotifyURL: "https://example.invalid/notify", PrivateKey: key, PlatformKey: &key.PublicKey, APIv3Key: []byte(strings.Repeat("a", 32))})
	if e != nil {
		t.Fatal(e)
	}
	return p
}
func encryptedWx(t *testing.T, w *Wechat, key *rsa.PrivateKey, transaction []byte, delta int64) (http.Header, []byte) {
	t.Helper()
	block, _ := aes.NewCipher(w.cfg.APIv3Key)
	gcm, _ := cipher.NewGCM(block)
	nonce := "123456789012"
	encrypted := gcm.Seal(nil, []byte(nonce), transaction, []byte("transaction"))
	body, _ := json.Marshal(map[string]any{"event_type": "TRANSACTION.SUCCESS", "resource": map[string]string{"algorithm": "AEAD_AES_256_GCM", "nonce": nonce, "associated_data": "transaction", "ciphertext": base64.StdEncoding.EncodeToString(encrypted)}})
	at := fmt.Sprint(time.Now().Unix() + delta)
	header := http.Header{}
	header.Set("Wechatpay-Timestamp", at)
	header.Set("Wechatpay-Nonce", "nonce00000001")
	header.Set("Wechatpay-Serial", w.cfg.PlatformSerial)
	sig, _ := signRSA(key, []byte(at+"\nnonce00000001\n"+string(body)+"\n"))
	header.Set("Wechatpay-Signature", sig)
	return header, body
}
func TestWechatSignedEncryptedCallbackRejectsStaleWrongSerialAndWrongMerchant(t *testing.T) {
	key := merchant(t)
	p := wx(t, key)
	s, pub := service(t, p)
	// Construct the created order without contacting any real merchant service.
	s.providers["wechat"] = &providerNamedStub{name: "wechat"}
	o := create(t, s, "wechat", "request-00000001")
	s.providers["wechat"] = p
	transaction := []byte(fmt.Sprintf(`{"appid":%q,"mchid":%q,"out_trade_no":%q,"transaction_id":"wx-trade-1","trade_state":"SUCCESS","amount":{"total":2990,"currency":"CNY"}}`, p.cfg.AppID, p.cfg.MerchantID, o.ID))
	h, body := encryptedWx(t, p, key, transaction, 0)
	receipt, e := p.VerifyNotify(h, body)
	if e != nil || s.settle("wechat", receipt) != nil {
		t.Fatal("valid signed encrypted receipt failed", e)
	}
	got, _ := s.Get(o.ID, owner)
	if _, e = pro.Verify(got.License, pub); e != nil {
		t.Fatal(e)
	}
	for _, delta := range []int64{-600, 600} {
		header, data := encryptedWx(t, p, key, transaction, delta)
		if _, e := p.VerifyNotify(header, data); e == nil {
			t.Fatal("stale timestamp accepted")
		}
	}
	tampered := append([]byte{}, body...)
	tampered[len(tampered)-2] ^= 1
	if _, e := p.VerifyNotify(h, tampered); e == nil {
		t.Fatal("body signature tampering accepted")
	}
	badHeader := h.Clone()
	badHeader.Set("Wechatpay-Serial", "other00000001")
	if _, e := p.VerifyNotify(badHeader, body); e == nil {
		t.Fatal("unknown platform serial accepted")
	}
	badMerchant := bytes.ReplaceAll(transaction, []byte(p.cfg.MerchantID), []byte("9999999999"))
	header, data := encryptedWx(t, p, key, badMerchant, 0)
	if _, e := p.VerifyNotify(header, data); e == nil {
		t.Fatal("wrong merchant accepted")
	}
	handler := NewHandler(s)
	req := httptest.NewRequest("POST", "/v1/commerce/notify/wechat", bytes.NewReader(body))
	req.Header = h
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, req)
	if response.Code != 200 {
		t.Fatal(response.Code)
	}
}

type providerNamedStub struct{ name string }

func (p *providerNamedStub) Name() string { return p.name }
func (p *providerNamedStub) Prepare(context.Context, Order) (Checkout, error) {
	return Checkout{Kind: "redirect", Value: "https://example.invalid/checkout"}, nil
}
func (p *providerNamedStub) Query(context.Context, Order) (Receipt, error) {
	return Receipt{}, ErrInvalid
}

func TestSignedAlipayQueryAndWechatRequestUseTheStoredAmount(t *testing.T) {
	key := merchant(t)
	a := ali(t, key)
	o := Order{ID: strings.Repeat("a", 32), AmountMinor: 2990, Currency: "CNY", ExpiresAt: time.Now().Unix() + 1800}
	response := []byte(fmt.Sprintf(`{"code":"10000","out_trade_no":%q,"trade_no":"query-trade","trade_status":"TRADE_SUCCESS","total_amount":"29.90"}`, o.ID))
	signature, _ := signRSA(key, response)
	ts := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if r.PostForm.Get("method") != "alipay.trade.query" || verifyRSA(&key.PublicKey, canonical(r.PostForm, false), r.PostForm.Get("sign")) != nil {
			t.Error("query request not signed")
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"alipay_trade_query_response": json.RawMessage(response), "sign": signature})
	}))
	defer ts.Close()
	a.gateway = ts.URL
	a.client = ts.Client()
	receipt, e := a.Query(context.Background(), o)
	if e != nil || receipt.AmountMinor != 2990 || receipt.State != "PAID" {
		t.Fatal(receipt, e)
	}
	p := wx(t, key)
	wechatServer := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Amount struct {
				Total int64 `json:"total"`
			} `json:"amount"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil || body.Amount.Total != 2990 {
			t.Error("wrong stored amount")
		}
		data := []byte(`{"code_url":"weixin://wxpay/bizpayurl?pr=fixture"}`)
		at := fmt.Sprint(time.Now().Unix())
		sig, _ := signRSA(key, []byte(at+"\nnonce00000001\n"+string(data)+"\n"))
		w.Header().Set("Wechatpay-Timestamp", at)
		w.Header().Set("Wechatpay-Nonce", "nonce00000001")
		w.Header().Set("Wechatpay-Serial", p.cfg.PlatformSerial)
		w.Header().Set("Wechatpay-Signature", sig)
		_, _ = w.Write(data)
	}))
	defer wechatServer.Close()
	p.endpoint = wechatServer.URL
	p.client = wechatServer.Client()
	checkout, e := p.Prepare(context.Background(), o)
	if e != nil || checkout.Kind != "native_qr" {
		t.Fatal(checkout, e)
	}
}
