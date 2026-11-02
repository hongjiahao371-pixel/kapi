package pro

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFreeRestartAndLegacyPreservation(t *testing.T) {
	dir := t.TempDir()
	m, e := Open(dir, false)
	if e != nil {
		t.Fatal(e)
	}
	if m.Enabled() || m.Status().PaymentsEnabled {
		t.Fatal("fresh install unlocked")
	}
	m, e = Open(dir, true)
	if e != nil || m.Enabled() {
		t.Fatal("fresh restart incorrectly gifted", e)
	}
	for key := range Free {
		if !m.Allows(key) {
			t.Fatal(key)
		}
	}
	for key := range Premium {
		if m.Allows(key) {
			t.Fatal(key)
		}
	}
	if m.Allows("unknown") {
		t.Fatal("unknown feature")
	}
	if m.Activate("GZ1.invalid.invalid") == nil || m.Enabled() {
		t.Fatal("invalid activation unlocked")
	}
	legacy, e := Open(t.TempDir(), true)
	if e != nil || !legacy.Enabled() {
		t.Fatal("legacy grant lost", e)
	}
	if legacy.Activate("bad") == nil || !legacy.Enabled() {
		t.Fatal("bad activation erased legacy")
	}
}

func TestProductCatalogueNeverRevokesLegacyAndForgedOrderCannotActivate(t *testing.T) {
	catalogue := ProductCatalogue()
	if PolicyVersion != 1 || catalogue.Product.AmountMinor != 2990 || catalogue.Product.ID != ProductID || len(catalogue.Platforms["nas"]) != 3 || len(Premium) != 10 {
		t.Fatal("catalogue drift")
	}
	for _, old := range []bool{false, true} {
		dir := t.TempDir()
		m, e := Open(dir, old)
		if e != nil {
			t.Fatal(e)
		}
		before, _ := os.ReadFile(filepath.Join(dir, "entitlements.json"))
		if m.ActivatePaidOrder([]byte(`{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","state":"PAID","product_id":"guizhang-pro-lifetime","currency":"CNY","amount_minor":2990,"activation_code":"paid=true"}`), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") == nil {
			t.Fatal("unsigned paid claim unlocked")
		}
		after, _ := os.ReadFile(filepath.Join(dir, "entitlements.json"))
		if string(before) != string(after) || m.Enabled() != old {
			t.Fatal("invalid order changed existing rights")
		}
		reopened, e := Open(dir, false)
		if e != nil || reopened.Enabled() != old {
			t.Fatal("catalogue upgrade changed legacy rights", e)
		}
	}
}
func TestSignedLicense(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	pub := base64.StdEncoding.EncodeToString(der)
	sign := func(p License) string {
		b, _ := json.Marshal(p)
		hash := sha256.Sum256(b)
		signature, _ := ecdsa.SignASN1(rand.Reader, key, hash[:])
		return "GZ1." + base64.RawURLEncoding.EncodeToString(b) + "." + base64.RawURLEncoding.EncodeToString(signature)
	}
	valid := License{"test-license-0001", "测试用户", "guizhang-pro", true}
	code := sign(valid)
	if v, e := Verify(code, pub); e != nil || v.Holder != valid.Holder {
		t.Fatal(e)
	}
	if _, e := Verify(code, PublicKey); e == nil {
		t.Fatal("other key accepted")
	}
	valid.Product = "other"
	if _, e := Verify(sign(valid), pub); e == nil {
		t.Fatal("other product")
	}
	parts := strings.Split(code, ".")
	parts[1] = base64.RawURLEncoding.EncodeToString([]byte("{}"))
	if _, e := Verify(strings.Join(parts, "."), pub); e == nil {
		t.Fatal("tampered payload")
	}
}
func TestDamagedEntitlementsNeverOverwrite(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "entitlements.json")
	_ = os.WriteFile(p, []byte("broken"), 0600)
	m, e := Open(dir, true)
	if e != nil || m.Enabled() || !m.Allows("data_access") {
		t.Fatal("license damage locked data or unlocked Pro", e)
	}
	b, _ := os.ReadFile(p)
	if string(b) != "broken" {
		t.Fatal("overwritten")
	}
}
