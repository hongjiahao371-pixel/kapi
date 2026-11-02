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
	"os"
	"strings"
	"testing"
)

func TestAfdianSignedRestoreRejectsReplayAndPreservesLegacyAndLedger(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	pub := base64.StdEncoding.EncodeToString(der)
	id, nonce := strings.Repeat("a", 32), strings.Repeat("b", 32)
	makeResponse := func(state string, revision int64, challenge string) []byte {
		hash := sha256.Sum256([]byte("redirect\nhttps://afdian.com/order/create"))
		p := orderProof{id, state, revision, challenge, "afdian", "android", ProductID, 2990, "CNY", true, hex.EncodeToString(hash[:])}
		b, _ := json.Marshal(p)
		sum := sha256.Sum256(b)
		sig, _ := ecdsa.SignASN1(rand.Reader, key, sum[:])
		proof := "GZO1." + base64.RawURLEncoding.EncodeToString(b) + "." + base64.RawURLEncoding.EncodeToString(sig)
		response, _ := json.Marshal(map[string]any{"id": id, "state": state, "channel": "afdian", "entitlement_proof": proof, "checkout": map[string]string{"kind": "redirect", "value": "https://afdian.com/order/create"}})
		return response
	}
	for _, gift := range []bool{false, true} {
		m, e := Open(t.TempDir(), gift)
		if e != nil {
			t.Fatal(e)
		}
		if e = m.activateVerifiedOrder(makeResponse("PAID", 2, nonce), id, nonce, pub); e != nil {
			t.Fatal(e)
		}
		if len(m.state.Orders) != 1 || m.state.LegacyGift != gift {
			t.Fatal("grant duplicated or legacy changed")
		}
		if e = m.activateVerifiedOrder(makeResponse("PAID", 2, nonce), id, nonce, pub); e != nil {
			t.Fatal("repeated restore", e)
		}
		before, _ := os.ReadFile(m.file)
		if e = m.activateVerifiedOrder(makeResponse("PAID", 2, strings.Repeat("c", 32)), id, nonce, pub); e == nil {
			t.Fatal("wrong challenge accepted")
		}
		after, _ := os.ReadFile(m.file)
		if string(before) != string(after) {
			t.Fatal("unverified response changed rights")
		}
		if e = m.activateVerifiedOrder(makeResponse("REFUNDED", 3, nonce), id, nonce, pub); e != nil {
			t.Fatal(e)
		}
		if e = m.activateVerifiedOrder(makeResponse("PAID", 2, nonce), id, nonce, pub); e == nil {
			t.Fatal("old paid proof revived refund")
		}
		if m.state.LegacyGift != gift {
			t.Fatal("refund erased gift")
		}
		stored, e := Open(strings.TrimSuffix(m.file, "/entitlements.json"), false)
		if e != nil || len(stored.state.Orders) != 1 {
			t.Fatal("restore not durable", e)
		}
		if _, e = verifyOrderProof(stored.state.Orders[id], id, "", pub); e != nil {
			t.Fatal(e)
		}
		// Test key is deliberately not the shipped issuer: a customer cannot self-sign Pro.
		if !gift && stored.Enabled() {
			t.Fatal("wrong issuer unlocked")
		}
	}
}
