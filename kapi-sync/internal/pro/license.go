package pro

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"unicode/utf16"
)

const PublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETWQ/vgIUK7zPYZFxdWzY7DxaHw6RUVefPoPlbAFC8Fj7dyuliHrCwu8jn1AdWQ/XriPfN7JCgFZs4xk5eM6vrA=="

type License struct {
	ID        string `json:"id"`
	Holder    string `json:"holder"`
	Product   string `json:"product"`
	Permanent bool   `json:"permanent"`
}

func Verify(code, publicKey string) (License, error) {
	var p License
	invalid := errors.New("激活码无效，请核对完整内容")
	if len(code) < 32 || len(code) > 4096 {
		return p, invalid
	}
	parts := strings.Split(strings.TrimSpace(code), ".")
	if len(parts) != 3 || parts[0] != "GZ1" {
		return p, invalid
	}
	content, e := base64.RawURLEncoding.DecodeString(parts[1])
	if e != nil {
		return p, invalid
	}
	signature, e := base64.RawURLEncoding.DecodeString(parts[2])
	if e != nil {
		return p, invalid
	}
	der, e := base64.StdEncoding.DecodeString(publicKey)
	if e != nil {
		return p, invalid
	}
	key, e := x509.ParsePKIXPublicKey(der)
	if e != nil {
		return p, invalid
	}
	pub, ok := key.(*ecdsa.PublicKey)
	if !ok || pub.Curve.Params().BitSize != 256 {
		return p, invalid
	}
	sum := sha256.Sum256(content)
	if !ecdsa.VerifyASN1(pub, sum[:], signature) {
		return p, invalid
	}
	if json.Unmarshal(content, &p) != nil || p.Product != "guizhang-pro" || !p.Permanent {
		return License{}, invalid
	}
	rawHolder := p.Holder
	p.Holder = strings.TrimSpace(p.Holder)
	if n := len(utf16.Encode([]rune(p.ID))); n < 8 || n > 80 {
		return License{}, invalid
	}
	if n := len(utf16.Encode([]rune(p.Holder))); n < 1 || n > 80 {
		return License{}, invalid
	}
	for _, r := range p.ID + rawHolder {
		if r < 32 || r == 127 {
			return License{}, invalid
		}
	}
	return p, nil
}
