// Package security owns transport and unauthenticated request safeguards.
package security

import (
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// ResolvePanelToken keeps generated credentials across restarts, without logging them.
func ResolvePanelToken(dir, configured string) (string, error) {
	if configured != "" {
		return configured, nil
	}
	path := filepath.Join(dir, "panel-token")
	if b, e := os.ReadFile(path); e == nil {
		t := strings.TrimSpace(string(b))
		if len(t) < 32 {
			return "", errors.New("invalid persisted panel token")
		}
		return t, nil
	} else if !errors.Is(e, os.ErrNotExist) {
		return "", e
	}
	raw := make([]byte, 32)
	if _, e := rand.Read(raw); e != nil {
		return "", e
	}
	token := hex.EncodeToString(raw)
	f, e := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		return "", e
	}
	_, e = f.WriteString(token + "\n")
	if e == nil {
		e = f.Sync()
	}
	ce := f.Close()
	if e != nil {
		return "", e
	}
	return token, ce
}
func TLSConfig(certFile, keyFile string) (*tls.Config, error) {
	if certFile == "" || keyFile == "" {
		return nil, errors.New("HTTPS requires both certificate and private key")
	}
	cert, e := tls.LoadX509KeyPair(certFile, keyFile)
	if e != nil {
		return nil, e
	}
	leaf, e := x509.ParseCertificate(cert.Certificate[0])
	if e != nil {
		return nil, e
	}
	now := time.Now()
	if now.Before(leaf.NotBefore) || now.After(leaf.NotAfter) {
		return nil, errors.New("HTTPS certificate is not currently valid")
	}
	return &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{cert}}, nil
}

type attempt struct {
	count int
	until time.Time
}
type Guard struct {
	HSTS     bool // Off during dual-port HTTP/HTTPS compatibility; it is host-wide in browsers.
	mu       sync.Mutex
	failures map[string]attempt
	now      func() time.Time
}

func NewGuard() *Guard { return &Guard{failures: map[string]attempt{}, now: time.Now} }
func (g *Guard) key(r *http.Request) string {
	host, _, e := net.SplitHostPort(r.RemoteAddr)
	if e != nil {
		host = r.RemoteAddr
	}
	// Never trust caller-supplied forwarding headers on a directly exposed listener.
	kind := "sync"
	p := r.URL.Path
	if strings.HasSuffix(p, "/v1/pair") {
		kind = "pair"
	} else if p == "/v1/family/join" {
		kind = "invite"
	} else if strings.Contains(p, "/v1/panel/") || strings.HasPrefix(p, "/v1/family/") {
		kind = "panel"
	}
	return host + "/" + kind
}
func (g *Guard) blocked(key string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	v := g.failures[key]
	return v.count >= 12 && g.now().Before(v.until)
}
func (g *Guard) failure(key string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.now()
	for k, v := range g.failures {
		if !now.Before(v.until) {
			delete(g.failures, k)
		}
	}
	if len(g.failures) >= 4096 {
		return
	} // bounded memory; fixed network deployment
	v := g.failures[key]
	if v.until.IsZero() {
		v.until = now.Add(time.Minute)
	}
	v.count++
	g.failures[key] = v
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(s int) { w.status = s; w.ResponseWriter.WriteHeader(s) }
func (g *Guard) Wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		// Per-listener TLS proof only; a forged X-Forwarded-Proto never enables HSTS.
		if r.TLS != nil && g.HSTS {
			w.Header().Set("Strict-Transport-Security", "max-age=2592000")
		}
		q := r.URL.Query()
		for _, name := range []string{"token", "access_token", "api_key"} {
			if q.Has(name) {
				w.WriteHeader(400)
				_ = json.NewEncoder(w).Encode(map[string]string{"message": "请通过请求头提供凭据，不要将令牌放进网址"})
				return
			}
		}
		key := g.key(r)
		// Public UI/health are never locked out by a failed sign-in.
		protected := strings.Contains(r.URL.Path, "/v1/") && !strings.HasSuffix(r.URL.Path, "/v1/health")
		if protected && g.blocked(key) {
			w.Header().Set("Retry-After", "60")
			w.WriteHeader(429)
			return
		}
		rec := &statusWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(rec, r)
		failed := rec.status == 401 || (rec.status == 403 && (strings.HasSuffix(r.URL.Path, "/v1/pair") || r.URL.Path == "/v1/family/join"))
		if failed {
			g.failure(key)
		}
	})
}
