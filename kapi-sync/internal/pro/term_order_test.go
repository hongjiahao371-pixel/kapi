package pro

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"strings"
	"testing"
)

func TestTermRestoreUsesLatestAccountRevisionAndCannotReviveRefund(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	pub := base64.StdEncoding.EncodeToString(der)
	nonce := strings.Repeat("b", 32)
	scope := strings.Repeat("c", 64)
	first, second := strings.Repeat("a", 32), strings.Repeat("e", 32)
	makeResponse := func(id string, rev, group, until int64, state string) []byte {
		hash := sha256.Sum256([]byte("redirect\nhttps://afdian.com/order/create"))
		p := orderProof{ID: id, State: state, Revision: rev, Nonce: nonce, Channel: "afdian", Platform: "android", Product: ProductCatalogue().Pricing.Offers[0].ID, Amount: 690, Currency: "CNY", CheckoutHash: hex.EncodeToString(hash[:]), Scope: scope, EntitlementRevision: group, IssuedAt: 1790000000, ValidUntil: until}
		b, _ := json.Marshal(p)
		sum := sha256.Sum256(b)
		sig, _ := ecdsa.SignASN1(rand.Reader, key, sum[:])
		proof := "GZO2." + base64.RawURLEncoding.EncodeToString(b) + "." + base64.RawURLEncoding.EncodeToString(sig)
		body, _ := json.Marshal(map[string]any{"product_id": p.Product, "amount_minor": p.Amount, "currency": "CNY", "platform": "android", "id": id, "state": state, "channel": "afdian", "entitlement_proof": proof, "checkout": map[string]string{"kind": "redirect", "value": "https://afdian.com/order/create"}})
		return body
	}
	m, e := Open(t.TempDir(), false)
	if e != nil {
		t.Fatal(e)
	}
	if e = m.activateVerifiedOrder(makeResponse(first, 2, 2, 1800000000, "PAID"), first, nonce, pub); e != nil {
		t.Fatal(e)
	}
	if e = m.activateVerifiedOrder(makeResponse(second, 2, 4, 1803000000, "PAID"), second, nonce, pub); e != nil {
		t.Fatal(e)
	}
	perm, until := orderEntitlement(m.state.Orders, pub, 1790000000)
	if perm || until != 1803000000 {
		t.Fatal("restore counted both deadlines", perm, until)
	}
	if e = m.activateVerifiedOrder(makeResponse(second, 3, 5, 0, "REFUNDED"), second, nonce, pub); e != nil {
		t.Fatal(e)
	}
	perm, until = orderEntitlement(m.state.Orders, pub, 1790000000)
	if perm || until != 0 {
		t.Fatal("old other-order proof defeated refund")
	}
	if e = m.activateVerifiedOrder(makeResponse(first, 2, 2, 1800000000, "PAID"), first, nonce, pub); e == nil {
		t.Fatal("cross-order replay revived refund")
	}
	reopened, e := Open(strings.TrimSuffix(m.file, "/entitlements.json"), false)
	if e != nil {
		t.Fatal(e)
	}
	perm, until = orderEntitlement(reopened.state.Orders, pub, 1790000000)
	if perm || until != 0 {
		t.Fatal("restart revived expired purchase")
	}
	if !Allows("data_access", false) || !Allows("family_join", false) || !Allows("backup", false) {
		t.Fatal("expiry locks user data")
	}
}
