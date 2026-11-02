package commerce

import (
	"context"
	"crypto"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"kapi/sync/internal/pro"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

func signRSA(key *rsa.PrivateKey, b []byte) (string, error) {
	h := sha256.Sum256(b)
	s, e := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, h[:])
	return base64.StdEncoding.EncodeToString(s), e
}
func verifyRSA(key *rsa.PublicKey, b []byte, sign string) error {
	if key == nil || key.N.BitLen() < 2048 {
		return ErrInvalid
	}
	decoded, e := base64.StdEncoding.DecodeString(sign)
	if e != nil {
		return e
	}
	h := sha256.Sum256(b)
	return rsa.VerifyPKCS1v15(key, crypto.SHA256, h[:], decoded)
}
func money(v int64) string { return fmt.Sprintf("%d.%02d", v/100, v%100) }
func minor(value string) (int64, error) {
	parts := strings.Split(value, ".")
	if len(parts) > 2 || len(parts[0]) < 1 || len(parts[0]) > 10 {
		return 0, ErrInvalid
	}
	fraction := "00"
	if len(parts) == 2 {
		if len(parts[1]) < 1 || len(parts[1]) > 2 {
			return 0, ErrInvalid
		}
		fraction = parts[1] + strings.Repeat("0", 2-len(parts[1]))
	}
	for _, c := range parts[0] + fraction {
		if c < '0' || c > '9' {
			return 0, ErrInvalid
		}
	}
	a, e := strconv.ParseInt(parts[0]+fraction, 10, 64)
	if e != nil || a <= 0 {
		return 0, ErrInvalid
	}
	return a, nil
}
func canonical(v url.Values, callback bool) []byte {
	keys := make([]string, 0, len(v))
	for k, values := range v {
		if k == "sign" || (callback && k == "sign_type") || len(values) == 0 || values[0] == "" {
			continue
		}
		keys = append(keys, k)
	}
	sort.Strings(keys)
	parts := []string{}
	for _, k := range keys {
		parts = append(parts, k+"="+v.Get(k))
	}
	return []byte(strings.Join(parts, "&"))
}
func secureURL(value string) bool {
	u, e := url.Parse(value)
	return e == nil && u.Scheme == "https" && u.Hostname() != "" && u.User == nil && u.Fragment == ""
}
func fixedClient() *http.Client {
	return &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

type AlipayConfig struct {
	AppID, SellerID, NotifyURL, ReturnURL string
	PrivateKey                            *rsa.PrivateKey
	PublicKey                             *rsa.PublicKey
	Sandbox                               bool
}
type Alipay struct {
	cfg     AlipayConfig
	gateway string
	client  *http.Client
	now     func() time.Time
}

func NewAlipay(c AlipayConfig) (*Alipay, error) {
	if !validAtom(c.AppID, 8, 64) || !validAtom(c.SellerID, 8, 64) || !secureURL(c.NotifyURL) || (c.ReturnURL != "" && !secureURL(c.ReturnURL)) || c.PrivateKey == nil || c.PublicKey == nil || c.PrivateKey.N.BitLen() < 2048 || c.PublicKey.N.BitLen() < 2048 {
		return nil, ErrInvalid
	}
	gateway := "https://openapi.alipay.com/gateway.do"
	if c.Sandbox {
		gateway = "https://openapi-sandbox.dl.alipaydev.com/gateway.do"
	}
	return &Alipay{cfg: c, gateway: gateway, client: fixedClient(), now: time.Now}, nil
}
func (a *Alipay) Name() string { return "alipay" }
func (a *Alipay) params(method string, biz any) (url.Values, error) {
	b, e := json.Marshal(biz)
	if e != nil {
		return nil, e
	}
	tz := time.FixedZone("CST", 8*3600)
	v := url.Values{"app_id": {a.cfg.AppID}, "method": {method}, "format": {"JSON"}, "charset": {"utf-8"}, "sign_type": {"RSA2"}, "timestamp": {a.now().In(tz).Format("2006-01-02 15:04:05")}, "version": {"1.0"}, "biz_content": {string(b)}}
	return v, nil
}
func (a *Alipay) Prepare(_ context.Context, o Order) (Checkout, error) {
	method, productCode := "alipay.trade.wap.pay", "QUICK_WAP_WAY"
	if o.Platform == "android" {
		method = "alipay.trade.app.pay"
		productCode = "QUICK_MSECURITY_PAY"
	}
	v, e := a.params(method, map[string]any{"out_trade_no": o.ID, "total_amount": money(o.AmountMinor), "subject": pro.ProductCatalogue().Product.Title, "product_code": productCode, "timeout_express": "30m"})
	if e != nil {
		return Checkout{}, e
	}
	v.Set("notify_url", a.cfg.NotifyURL)
	if a.cfg.ReturnURL != "" {
		v.Set("return_url", a.cfg.ReturnURL)
	}
	sign, e := signRSA(a.cfg.PrivateKey, canonical(v, false))
	if e != nil {
		return Checkout{}, e
	}
	v.Set("sign", sign)
	if o.Platform == "android" {
		return Checkout{Kind: "alipay_app", Value: v.Encode()}, nil
	}
	return Checkout{Kind: "redirect", Value: a.gateway + "?" + v.Encode()}, nil
}
func (a *Alipay) Query(ctx context.Context, o Order) (Receipt, error) {
	v, e := a.params("alipay.trade.query", map[string]string{"out_trade_no": o.ID})
	if e != nil {
		return Receipt{}, e
	}
	sign, e := signRSA(a.cfg.PrivateKey, canonical(v, false))
	if e != nil {
		return Receipt{}, e
	}
	v.Set("sign", sign)
	req, e := http.NewRequestWithContext(ctx, "POST", a.gateway, strings.NewReader(v.Encode()))
	if e != nil {
		return Receipt{}, e
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, e := a.client.Do(req)
	if e != nil {
		return Receipt{}, e
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return Receipt{}, ErrInvalid
	}
	data, e := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if e != nil {
		return Receipt{}, e
	}
	var envelope map[string]json.RawMessage
	if json.Unmarshal(data, &envelope) != nil {
		return Receipt{}, ErrInvalid
	}
	var signature string
	if json.Unmarshal(envelope["sign"], &signature) != nil || verifyRSA(a.cfg.PublicKey, envelope["alipay_trade_query_response"], signature) != nil {
		return Receipt{}, ErrInvalid
	}
	var result struct{ Code, OutTradeNo, TradeNo, TradeStatus, TotalAmount string }
	// Protocol field names intentionally explicit, never a loose search for "success".
	var body struct {
		Code        string `json:"code"`
		OutTradeNo  string `json:"out_trade_no"`
		TradeNo     string `json:"trade_no"`
		TradeStatus string `json:"trade_status"`
		TotalAmount string `json:"total_amount"`
	}
	if json.Unmarshal(envelope["alipay_trade_query_response"], &body) != nil || body.Code != "10000" || body.OutTradeNo != o.ID {
		return Receipt{}, ErrInvalid
	}
	result.Code = body.Code
	result.OutTradeNo = body.OutTradeNo
	result.TradeNo = body.TradeNo
	result.TradeStatus = body.TradeStatus
	result.TotalAmount = body.TotalAmount
	return alipayReceipt(result.OutTradeNo, result.TradeNo, result.TradeStatus, result.TotalAmount)
}
func alipayReceipt(id, trade, status, amount string) (Receipt, error) {
	n, e := minor(amount)
	if e != nil {
		return Receipt{}, e
	}
	state := ""
	switch status {
	case "TRADE_SUCCESS", "TRADE_FINISHED":
		state = "PAID"
	case "TRADE_CLOSED":
		state = "CLOSED"
	case "WAIT_BUYER_PAY":
		state = "PENDING"
	default:
		return Receipt{}, ErrInvalid
	}
	return Receipt{OrderID: id, TradeID: trade, Currency: "CNY", AmountMinor: n, State: state}, nil
}
func (a *Alipay) VerifyNotify(v url.Values) (Receipt, error) {
	for _, values := range v {
		if len(values) != 1 {
			return Receipt{}, ErrInvalid
		}
	}
	if v.Get("sign_type") != "RSA2" || v.Get("app_id") != a.cfg.AppID || v.Get("seller_id") != a.cfg.SellerID {
		return Receipt{}, ErrInvalid
	}
	if e := verifyRSA(a.cfg.PublicKey, canonical(v, true), v.Get("sign")); e != nil {
		return Receipt{}, ErrInvalid
	}
	return alipayReceipt(v.Get("out_trade_no"), v.Get("trade_no"), v.Get("trade_status"), v.Get("total_amount"))
}

type WechatConfig struct {
	AppID, MerchantID, MerchantSerial, PlatformSerial, NotifyURL string
	PrivateKey                                                   *rsa.PrivateKey
	PlatformKey                                                  *rsa.PublicKey
	APIv3Key                                                     []byte
}
type Wechat struct {
	cfg      WechatConfig
	endpoint string
	client   *http.Client
	now      func() time.Time
}

func NewWechat(c WechatConfig) (*Wechat, error) {
	if !validAtom(c.AppID, 8, 64) || !validAtom(c.MerchantID, 6, 64) || !validAtom(c.MerchantSerial, 8, 128) || !validAtom(c.PlatformSerial, 8, 128) || !secureURL(c.NotifyURL) || len(c.APIv3Key) != 32 || c.PrivateKey == nil || c.PlatformKey == nil || c.PrivateKey.N.BitLen() < 2048 || c.PlatformKey.N.BitLen() < 2048 {
		return nil, ErrInvalid
	}
	return &Wechat{cfg: c, endpoint: "https://api.mch.weixin.qq.com", client: fixedClient(), now: time.Now}, nil
}
func (w *Wechat) Name() string { return "wechat" }
func (w *Wechat) verify(headers http.Header, body []byte) error {
	timestamp := headers.Get("Wechatpay-Timestamp")
	at, e := strconv.ParseInt(timestamp, 10, 64)
	if e != nil {
		return ErrInvalid
	}
	delta := w.now().Unix() - at
	nonce := headers.Get("Wechatpay-Nonce")
	if delta < -300 || delta > 300 || !validAtom(nonce, 8, 128) || headers.Get("Wechatpay-Serial") != w.cfg.PlatformSerial {
		return ErrInvalid
	}
	return verifyRSA(w.cfg.PlatformKey, []byte(timestamp+"\n"+nonce+"\n"+string(body)+"\n"), headers.Get("Wechatpay-Signature"))
}
func (w *Wechat) request(ctx context.Context, method, path string, body []byte) ([]byte, error) {
	nonce, e := randomID()
	if e != nil {
		return nil, e
	}
	at := strconv.FormatInt(w.now().Unix(), 10)
	sign, e := signRSA(w.cfg.PrivateKey, []byte(method+"\n"+path+"\n"+at+"\n"+nonce+"\n"+string(body)+"\n"))
	if e != nil {
		return nil, e
	}
	req, e := http.NewRequestWithContext(ctx, method, w.endpoint+path, strings.NewReader(string(body)))
	if e != nil {
		return nil, e
	}
	req.Header.Set("Authorization", fmt.Sprintf("WECHATPAY2-SHA256-RSA2048 mchid=\"%s\",nonce_str=\"%s\",timestamp=\"%s\",serial_no=\"%s\",signature=\"%s\"", w.cfg.MerchantID, nonce, at, w.cfg.MerchantSerial, sign))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	response, e := w.client.Do(req)
	if e != nil {
		return nil, e
	}
	defer response.Body.Close()
	data, e := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if e != nil {
		return nil, e
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 || w.verify(response.Header, data) != nil {
		return nil, ErrInvalid
	}
	return data, nil
}
func (w *Wechat) Prepare(ctx context.Context, o Order) (Checkout, error) {
	body, e := json.Marshal(map[string]any{"appid": w.cfg.AppID, "mchid": w.cfg.MerchantID, "description": pro.ProductCatalogue().Product.Title, "out_trade_no": o.ID, "time_expire": time.Unix(o.ExpiresAt, 0).Format(time.RFC3339), "notify_url": w.cfg.NotifyURL, "amount": map[string]any{"total": o.AmountMinor, "currency": o.Currency}})
	if e != nil {
		return Checkout{}, e
	}
	path := "/v3/pay/transactions/native"
	if o.Platform == "android" {
		path = "/v3/pay/transactions/app"
	}
	data, e := w.request(ctx, "POST", path, body)
	if e != nil {
		return Checkout{}, e
	}
	if o.Platform == "android" {
		var result struct {
			PrepayID string `json:"prepay_id"`
		}
		if json.Unmarshal(data, &result) != nil || !validAtom(result.PrepayID, 8, 128) {
			return Checkout{}, ErrInvalid
		}
		nonce, e := randomID()
		if e != nil {
			return Checkout{}, e
		}
		at := strconv.FormatInt(w.now().Unix(), 10)
		signature, e := signRSA(w.cfg.PrivateKey, []byte(w.cfg.AppID+"\n"+at+"\n"+nonce+"\n"+result.PrepayID+"\n"))
		if e != nil {
			return Checkout{}, e
		}
		value, _ := json.Marshal(map[string]string{"appid": w.cfg.AppID, "partnerid": w.cfg.MerchantID, "prepayid": result.PrepayID, "package": "Sign=WXPay", "noncestr": nonce, "timestamp": at, "sign": signature, "sign_type": "RSA"})
		return Checkout{Kind: "wechat_app", Value: string(value)}, nil
	}
	var result struct {
		URL string `json:"code_url"`
	}
	if json.Unmarshal(data, &result) != nil || !strings.HasPrefix(result.URL, "weixin://wxpay/bizpayurl?") {
		return Checkout{}, ErrInvalid
	}
	return Checkout{Kind: "native_qr", Value: result.URL}, nil
}

func (w *Wechat) refreshAppCheckout(checkout Checkout) (Checkout, error) {
	var args map[string]string
	if checkout.Kind != "wechat_app" || json.Unmarshal([]byte(checkout.Value), &args) != nil || args["appid"] != w.cfg.AppID || args["partnerid"] != w.cfg.MerchantID || !validAtom(args["prepayid"], 8, 128) {
		return Checkout{}, ErrInvalid
	}
	nonce, e := randomID()
	if e != nil {
		return Checkout{}, e
	}
	at := strconv.FormatInt(w.now().Unix(), 10)
	sign, e := signRSA(w.cfg.PrivateKey, []byte(w.cfg.AppID+"\n"+at+"\n"+nonce+"\n"+args["prepayid"]+"\n"))
	if e != nil {
		return Checkout{}, e
	}
	args["noncestr"] = nonce
	args["timestamp"] = at
	args["sign"] = sign
	value, e := json.Marshal(args)
	return Checkout{Kind: "wechat_app", Value: string(value)}, e
}

type wxTransaction struct {
	AppID      string `json:"appid"`
	MerchantID string `json:"mchid"`
	OrderID    string `json:"out_trade_no"`
	TradeID    string `json:"transaction_id"`
	State      string `json:"trade_state"`
	Amount     struct {
		Total    int64  `json:"total"`
		Currency string `json:"currency"`
	} `json:"amount"`
}

func (w *Wechat) transaction(b []byte) (Receipt, error) {
	var t wxTransaction
	if json.Unmarshal(b, &t) != nil || t.AppID != w.cfg.AppID || t.MerchantID != w.cfg.MerchantID {
		return Receipt{}, ErrInvalid
	}
	state := ""
	switch t.State {
	case "SUCCESS":
		state = "PAID"
	case "CLOSED", "REVOKED":
		state = "CLOSED"
	case "NOTPAY", "USERPAYING", "PAYERROR", "REFUND":
		state = "PENDING"
	default:
		return Receipt{}, ErrInvalid
	}
	return Receipt{OrderID: t.OrderID, TradeID: t.TradeID, Currency: t.Amount.Currency, AmountMinor: t.Amount.Total, State: state}, nil
}
func (w *Wechat) Query(ctx context.Context, o Order) (Receipt, error) {
	data, e := w.request(ctx, "GET", "/v3/pay/transactions/out-trade-no/"+url.PathEscape(o.ID)+"?mchid="+url.QueryEscape(w.cfg.MerchantID), nil)
	if e != nil {
		return Receipt{}, e
	}
	r, e := w.transaction(data)
	if e == nil && r.OrderID != o.ID {
		return Receipt{}, ErrInvalid
	}
	return r, e
}
func (w *Wechat) VerifyNotify(headers http.Header, body []byte) (Receipt, error) {
	if e := w.verify(headers, body); e != nil {
		return Receipt{}, ErrInvalid
	}
	var envelope struct {
		Event    string `json:"event_type"`
		Resource struct {
			Algorithm  string `json:"algorithm"`
			Nonce      string `json:"nonce"`
			Associated string `json:"associated_data"`
			Ciphertext string `json:"ciphertext"`
		} `json:"resource"`
	}
	if json.Unmarshal(body, &envelope) != nil || (envelope.Event != "TRANSACTION.SUCCESS" && envelope.Event != "REFUND.SUCCESS") || envelope.Resource.Algorithm != "AEAD_AES_256_GCM" || len(envelope.Resource.Nonce) != 12 {
		return Receipt{}, ErrInvalid
	}
	encrypted, e := base64.StdEncoding.DecodeString(envelope.Resource.Ciphertext)
	if e != nil {
		return Receipt{}, ErrInvalid
	}
	block, e := aes.NewCipher(w.cfg.APIv3Key)
	if e != nil {
		return Receipt{}, e
	}
	gcm, e := cipher.NewGCM(block)
	if e != nil {
		return Receipt{}, e
	}
	plaintext, e := gcm.Open(nil, []byte(envelope.Resource.Nonce), encrypted, []byte(envelope.Resource.Associated))
	if e != nil {
		return Receipt{}, errors.New("invalid encrypted notification")
	}
	if envelope.Event == "REFUND.SUCCESS" {
		var refund struct {
			MerchantID string `json:"mchid"`
			OrderID    string `json:"out_trade_no"`
			TradeID    string `json:"transaction_id"`
			ID         string `json:"refund_id"`
			State      string `json:"refund_status"`
			Amount     struct {
				Total    int64  `json:"total"`
				Refund   int64  `json:"refund"`
				Currency string `json:"currency"`
			} `json:"amount"`
		}
		if json.Unmarshal(plaintext, &refund) != nil || refund.MerchantID != w.cfg.MerchantID || refund.State != "SUCCESS" || (refund.Amount.Currency != "" && refund.Amount.Currency != "CNY") {
			return Receipt{}, ErrInvalid
		}
		return Receipt{OrderID: refund.OrderID, TradeID: refund.TradeID, Currency: "CNY", AmountMinor: refund.Amount.Total, State: "REFUND", RefundID: refund.ID, RefundMinor: refund.Amount.Refund}, nil
	}
	receipt, e := w.transaction(plaintext)
	if e == nil && receipt.State != "PAID" {
		return Receipt{}, ErrInvalid
	}
	return receipt, e
}
