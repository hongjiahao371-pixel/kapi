package security

import (
	"crypto/tls"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPanelCredentialPersistsPrivatelyAndConfiguredCredentialWins(t *testing.T) {
	dir := t.TempDir()
	a, e := ResolvePanelToken(dir, "")
	if e != nil || len(a) != 64 {
		t.Fatal(e)
	}
	b, e := ResolvePanelToken(dir, "")
	if e != nil || a != b {
		t.Fatal("credential changed on restart")
	}
	info, _ := os.Stat(filepath.Join(dir, "panel-token"))
	if info.Mode().Perm() != 0600 {
		t.Fatal("credential file not private")
	}
	c, _ := ResolvePanelToken(dir, "existing-token")
	if c != "existing-token" {
		t.Fatal("old credential replaced")
	}
}
func TestURLCredentialsAndForwardedTLSClaimsAreRejected(t *testing.T) {
	guard := NewGuard()
	h := guard.Wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) }))
	for _, p := range []string{"/v1/panel/summary?token=secret", "/family/space/shared/id/v1/panel/tx?access_token=secret", "/v1/changes?api_key=secret"} {
		r := httptest.NewRequest("GET", p, nil)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != 400 || strings.Contains(w.Body.String(), "secret") {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	r := httptest.NewRequest("GET", "/panel/", nil)
	r.Header.Set("X-Forwarded-Proto", "https")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Header().Get("Strict-Transport-Security") != "" || w.Header().Get("Referrer-Policy") != "no-referrer" || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal(w.Header())
	}
	guard.HSTS = true
	r.TLS = &tls.ConnectionState{}
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Header().Get("Strict-Transport-Security") == "" {
		t.Fatal("missing HSTS on TLS")
	}
}
func TestFailedPairAttemptsUseRealPeerAndExpireWithoutBlockingOtherClientsOrSync(t *testing.T) {
	guard := NewGuard()
	now := time.Now()
	guard.now = func() time.Time { return now }
	calls := 0
	h := guard.Wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if strings.HasSuffix(r.URL.Path, "/pair") {
			w.WriteHeader(403)
		} else {
			w.WriteHeader(200)
		}
	}))
	call := func(path, ip string) int {
		r := httptest.NewRequest("POST", path, nil)
		r.RemoteAddr = ip + ":1234"
		r.Header.Set("X-Forwarded-For", "spoofed")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w.Code
	}
	for i := 0; i < 12; i++ {
		if call("/v1/pair", "192.0.2.1") != 403 {
			t.Fatal(i)
		}
	}
	if call("/v1/pair", "192.0.2.1") != 429 || calls != 12 {
		t.Fatal("not throttled")
	}
	if call("/v1/changes", "192.0.2.1") != 200 || call("/v1/pair", "192.0.2.2") != 403 {
		t.Fatal("unrelated flow blocked")
	}
	now = now.Add(time.Minute)
	if call("/v1/pair", "192.0.2.1") != 403 {
		t.Fatal("did not expire")
	}
}
func TestTLSRequiresBothReadableFiles(t *testing.T) {
	if _, e := TLSConfig("", ""); e == nil {
		t.Fatal("missing TLS accepted")
	}
	if _, e := TLSConfig("missing", "missing"); e == nil {
		t.Fatal("invalid TLS accepted")
	}
}
