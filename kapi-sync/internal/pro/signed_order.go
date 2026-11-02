package pro

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"regexp"
	"strings"
)

type orderProof struct {
	ID           string `json:"id"`
	State        string `json:"state"`
	Revision     int64  `json:"revision"`
	Nonce        string `json:"nonce"`
	Channel      string `json:"channel"`
	Platform     string `json:"platform"`
	Product      string `json:"product_id"`
	Amount       int64  `json:"amount_minor"`
	Currency     string `json:"currency"`
	Permanent    bool   `json:"permanent"`
	CheckoutHash string `json:"checkout_sha256"`
}

func verifyOrderProof(proof, id, nonce, pub string) (orderProof, error) {
	invalid := errors.New("订单签名或状态未通过核验")
	var p orderProof
	if len(proof) > 16000 || !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(id) {
		return p, invalid
	}
	parts := strings.Split(proof, ".")
	if len(parts) != 3 || parts[0] != "GZO1" {
		return p, invalid
	}
	b, e := base64.RawURLEncoding.DecodeString(parts[1])
	if e != nil || len(b) > 8000 {
		return p, invalid
	}
	sig, e := base64.RawURLEncoding.DecodeString(parts[2])
	if e != nil {
		return p, invalid
	}
	der, e := base64.StdEncoding.DecodeString(pub)
	if e != nil {
		return p, invalid
	}
	key, e := x509.ParsePKIXPublicKey(der)
	if e != nil {
		return p, invalid
	}
	ec, ok := key.(*ecdsa.PublicKey)
	sum := sha256.Sum256(b)
	if !ok || !ecdsa.VerifyASN1(ec, sum[:], sig) {
		return p, invalid
	}
	if json.Unmarshal(b, &p) != nil || p.ID != id || p.Product != ProductID || p.Amount != PriceMinor || p.Currency != "CNY" || !p.Permanent || p.Platform != "android" || p.Revision < 1 {
		return p, invalid
	}
	if p.Channel != "alipay" && p.Channel != "wechat" && p.Channel != "afdian" {
		return p, invalid
	}
	if p.State != "CREATED" && p.State != "EXPIRED" && p.State != "PAID" && p.State != "CLOSED" && p.State != "REFUNDED" {
		return p, invalid
	}
	if !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(p.Nonce) || nonce != "" && p.Nonce != nonce {
		return p, invalid
	}
	return p, nil
}
func (m *Manager) ActivateVerifiedOrder(response []byte, id, nonce string) error {
	return m.activateVerifiedOrder(response, id, nonce, PublicKey)
}
func (m *Manager) activateVerifiedOrder(response []byte, id, nonce, pub string) error {
	invalid := errors.New("订单未通过核验，原有权益保持不变")
	if len(response) > 32768 || !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(nonce) {
		return invalid
	}
	var v struct {
		ID       string `json:"id"`
		State    string `json:"state"`
		Channel  string `json:"channel"`
		Proof    string `json:"entitlement_proof"`
		Checkout struct {
			Kind  string `json:"kind"`
			Value string `json:"value"`
		} `json:"checkout"`
	}
	if json.Unmarshal(response, &v) != nil {
		return invalid
	}
	p, e := verifyOrderProof(v.Proof, id, nonce, pub)
	if e != nil || v.ID != id || v.State != p.State || v.Channel != p.Channel {
		return invalid
	}
	hash := sha256.Sum256([]byte(v.Checkout.Kind + "\n" + v.Checkout.Value))
	if hex.EncodeToString(hash[:]) != p.CheckoutHash {
		return invalid
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if old := m.state.Orders[id]; old != "" {
		previous, e := verifyOrderProof(old, id, "", pub)
		if e != nil || p.Revision < previous.Revision || p.Revision == previous.Revision && p.State != previous.State || previous.State == "REFUNDED" && p.State != "REFUNDED" {
			return invalid
		}
	}
	next := m.state
	next.Orders = make(map[string]string, len(m.state.Orders)+1)
	for key, value := range m.state.Orders {
		next.Orders[key] = value
	}
	next.Orders[id] = v.Proof
	if e = m.save(next); e != nil {
		return e
	}
	m.state = next
	return nil
}
