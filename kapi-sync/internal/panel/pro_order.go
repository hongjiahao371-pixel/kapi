package panel

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// This is an operator-configured publisher origin, never a URL from a browser.
func (h *Handler) configureCommerce(base string) {
	u, e := url.Parse(base)
	if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return
	}
	h.commerceURL = strings.TrimRight(base, "/")
	h.commerceClient = &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}
func (h *Handler) proOrderActivate(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		w.WriteHeader(405)
		return
	}
	if h.commerceURL == "" {
		writeJSON(w, 503, map[string]string{"error": "purchase_service_unavailable", "message": "购买服务尚未配置，已有激活码仍可恢复权益"})
		return
	}
	var input struct {
		ID       string `json:"order_id"`
		Token    string `json:"order_token"`
		Recovery string `json:"recovery_code"`
	}
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096))
	d.DisallowUnknownFields()
	if d.Decode(&input) != nil || d.Decode(&struct{}{}) != io.EOF {
		writeJSON(w, 400, map[string]string{"error": "invalid_order"})
		return
	}
	if input.Recovery != "" {
		var capability struct {
			ID      string `json:"id"`
			Token   string `json:"token"`
			Channel string `json:"channel"`
		}
		b, e := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(input.Recovery, "GZB1."))
		if !strings.HasPrefix(input.Recovery, "GZB1.") || e != nil || len(b) > 2048 || json.Unmarshal(b, &capability) != nil {
			writeJSON(w, 400, map[string]string{"error": "invalid_order"})
			return
		}
		input.ID = capability.ID
		input.Token = capability.Token
	}
	if !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(input.ID) || !regexp.MustCompile(`^[A-Za-z0-9_-]{43,128}$`).MatchString(input.Token) {
		writeJSON(w, 400, map[string]string{"error": "invalid_order", "message": "请核对订单编号和购买凭据"})
		return
	}
	challenge := make([]byte, 16)
	if _, e := rand.Read(challenge); e != nil {
		writeJSON(w, 503, map[string]string{"error": "purchase_service_unavailable"})
		return
	}
	nonce := hex.EncodeToString(challenge)
	req, e := http.NewRequestWithContext(r.Context(), "GET", h.commerceURL+"/v1/commerce/orders/"+input.ID+"?receipt_nonce="+nonce, nil)
	if e != nil {
		writeJSON(w, 503, map[string]string{"error": "purchase_service_unavailable"})
		return
	}
	req.Header.Set("Authorization", "Bearer "+input.Token)
	res, e := h.commerceClient.Do(req)
	if e != nil {
		writeJSON(w, 503, map[string]string{"error": "purchase_service_unavailable", "message": "暂时无法核对订单，原有权益保持不变"})
		return
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		writeJSON(w, 409, map[string]string{"error": "order_not_verified", "message": "订单未通过核验，原有权益保持不变"})
		return
	}
	data, e := io.ReadAll(io.LimitReader(res.Body, 32769))
	if e != nil || len(data) > 32768 || h.st.Pro.ActivateVerifiedOrder(data, input.ID, nonce) != nil {
		writeJSON(w, 409, map[string]string{"error": "order_not_verified", "message": "订单未通过签名核验，原有权益保持不变"})
		return
	}
	status := h.st.Pro.Status()
	status.OrderRestoreAvailable = true
	writeJSON(w, 200, status)
}
