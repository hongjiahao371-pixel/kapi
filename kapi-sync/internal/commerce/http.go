package commerce

import (
	"encoding/json"
	"errors"
	"io"
	"kapi/sync/internal/pro"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

type Handler struct {
	s      *Service
	mu     sync.Mutex
	limits map[string]limit
}
type limit struct {
	at    int64
	count int
}

func NewHandler(s *Service) http.Handler { return &Handler{s: s, limits: map[string]limit{}} }
func reply(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func fail(w http.ResponseWriter, e error) {
	code := http.StatusServiceUnavailable
	message := "支付暂未开放或服务暂不可用，原有权益保持不变"
	publicError := "service_unavailable"
	switch {
	case errors.Is(e, ErrNotFound):
		code = 404
		publicError = "order_not_found"
		message = "订单不存在或凭据无效"
	case errors.Is(e, ErrInvalid):
		code = 400
		publicError = "invalid_request"
		message = "请核对购买请求"
	case errors.Is(e, ErrPendingOrder):
		code = 409
		publicError = "order_requires_reconciliation"
		message = "这笔订单已超过付款时限，请先核对支付结果或联系客服关闭，请勿重复付款"
	case errors.Is(e, ErrConflict):
		code = 409
		publicError = "order_conflict"
		message = "订单核对不一致，请勿重复付款"
	case e.Error() == "platform_not_available":
		code = 409
		publicError = "platform_not_available"
		message = "当前仅开放安卓购买，其他端已有权益保留"
	case e.Error() == "store_purchase_required":
		code = 409
		publicError = "store_purchase_required"
		message = "此平台请使用商店内购"
	}
	reply(w, code, map[string]string{"error": publicError, "message": message})
}
func decode(w http.ResponseWriter, r *http.Request, value any) error {
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384))
	d.DisallowUnknownFields()
	if d.Decode(value) != nil {
		return ErrInvalid
	}
	if d.Decode(&struct{}{}) != io.EOF {
		return ErrInvalid
	}
	return nil
}
func (h *Handler) allow(remote string) bool {
	host, _, e := net.SplitHostPort(remote)
	if e != nil {
		host = remote
	}
	now := time.Now().Unix() / 60
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.limits) > 4096 {
		for k, v := range h.limits {
			if v.at != now {
				delete(h.limits, k)
			}
		}
		if len(h.limits) > 4096 {
			return false
		}
	}
	v := h.limits[host]
	if v.at != now {
		v = limit{at: now}
	}
	v.count++
	h.limits[host] = v
	return v.count <= 30
}
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	path := r.URL.Path
	if path == "/healthz" && r.Method == "GET" {
		reply(w, 200, map[string]bool{"ok": true})
		return
	}
	if path == "/v1/commerce/catalog" && r.Method == "GET" {
		channels := []string{}
		h.s.mu.Lock()
		if h.s.enabled && !h.s.damaged {
			for channel := range h.s.providers {
				channels = append(channels, channel)
			}
		}
		h.s.mu.Unlock()
		sort.Strings(channels)
		reply(w, 200, map[string]any{"catalogue": pro.ProductCatalogue(), "payments_enabled": len(channels) > 0, "channels": channels, "activation": "signed-order-receipt", "receipt_protocol": "GZO1", "purchase_platforms": []string{"android"}, "store_purchase_platforms": []string{"ios", "harmony"}})
		return
	}
	if strings.HasPrefix(path, "/v1/commerce/notify/") {
		h.notify(w, r, strings.TrimPrefix(path, "/v1/commerce/notify/"))
		return
	}
	if path == "/v1/commerce/orders" && r.Method == "POST" {
		if !h.allow(r.RemoteAddr) {
			reply(w, 429, map[string]string{"error": "rate_limited", "message": "请求过于频繁，请稍后重试"})
			return
		}
		var in struct {
			ProductID string `json:"product_id"`
			Platform  string `json:"platform"`
			Channel   string `json:"channel"`
			RequestID string `json:"request_id"`
		}
		if e := decode(w, r, &in); e != nil {
			fail(w, e)
			return
		}
		order, e := h.s.Create(r.Context(), Bearer(r.Header.Get("Authorization")), in.RequestID, in.ProductID, in.Platform, in.Channel)
		if e != nil {
			fail(w, e)
			return
		}
		if nonce := r.URL.Query().Get("receipt_nonce"); nonce != "" {
			order, e = h.s.VerifiedOrder(order.ID, Bearer(r.Header.Get("Authorization")), nonce)
			if e != nil {
				fail(w, e)
				return
			}
		}
		reply(w, 200, order)
		return
	}
	if strings.HasPrefix(path, "/v1/commerce/orders/") {
		parts := strings.Split(strings.TrimPrefix(path, "/v1/commerce/orders/"), "/")
		if len(parts) > 2 || !validAtom(parts[0], 32, 32) {
			http.NotFound(w, r)
			return
		}
		token := Bearer(r.Header.Get("Authorization"))
		var order OrderView
		var e error
		if len(parts) == 1 && r.Method == "GET" {
			order, e = h.s.Get(parts[0], token)
		} else if len(parts) == 2 && parts[1] == "afdian-query" && r.Method == "POST" {
			if !h.allow(r.RemoteAddr) {
				reply(w, 429, map[string]string{"error": "rate_limited"})
				return
			}
			var in struct {
				TradeNo string `json:"trade_no"`
			}
			if e = decode(w, r, &in); e == nil {
				order, e = h.s.RefreshAfdian(r.Context(), parts[0], token, in.TradeNo)
			}
		} else if len(parts) == 2 && parts[1] == "refresh" && r.Method == "POST" {
			if !h.allow(r.RemoteAddr) {
				reply(w, 429, map[string]string{"error": "rate_limited"})
				return
			}
			order, e = h.s.Refresh(r.Context(), parts[0], token)
		} else {
			w.WriteHeader(405)
			return
		}
		if e != nil {
			fail(w, e)
			return
		}
		if nonce := r.URL.Query().Get("receipt_nonce"); nonce != "" {
			order, e = h.s.VerifiedOrder(order.ID, Bearer(r.Header.Get("Authorization")), nonce)
			if e != nil {
				fail(w, e)
				return
			}
		}
		reply(w, 200, order)
		return
	}
	http.NotFound(w, r)
}
func (h *Handler) notify(w http.ResponseWriter, r *http.Request, channel string) {
	if r.Method != "POST" {
		w.WriteHeader(405)
		return
	}
	h.s.mu.Lock()
	provider := h.s.providers[channel]
	enabled := h.s.key != nil && !h.s.damaged
	h.s.mu.Unlock()
	if !enabled || provider == nil {
		w.WriteHeader(503)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	var receipt Receipt
	var e error
	switch p := provider.(type) {
	case *Alipay:
		if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/x-www-form-urlencoded") {
			w.WriteHeader(400)
			return
		}
		e = r.ParseForm()
		if e == nil && len(r.URL.RawQuery) > 0 {
			e = ErrInvalid
		}
		if e == nil {
			receipt, e = p.VerifyNotify(r.PostForm)
		}
	case *Afdian:
		if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
			w.WriteHeader(400)
			return
		}
		if !h.allow(r.RemoteAddr) {
			w.WriteHeader(429)
			return
		}
		body, err := io.ReadAll(r.Body)
		if err == nil {
			err = h.s.afdianNotify(r.Context(), p, body)
		}
		if err != nil {
			w.WriteHeader(400)
			return
		}
		reply(w, 200, map[string]any{"ec": 200, "em": ""})
		return
	case *Wechat:
		var body []byte
		body, e = io.ReadAll(r.Body)
		if e == nil {
			receipt, e = p.VerifyNotify(r.Header, body)
		}
	default:
		e = ErrInvalid
	}
	if e == nil {
		e = h.s.settle(channel, receipt)
	}
	if e != nil {
		w.WriteHeader(400)
		return
	}
	if channel == "alipay" {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = w.Write([]byte("success"))
	} else {
		reply(w, 200, map[string]string{"code": "SUCCESS", "message": "成功"})
	}
}
