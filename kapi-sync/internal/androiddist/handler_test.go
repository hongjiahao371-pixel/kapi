package androiddist

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fixture(t *testing.T) (string, string, []byte, Manifest, *ecdsa.PrivateKey) {
	t.Helper()
	dir := t.TempDir()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	pub := base64.StdEncoding.EncodeToString(der)
	apk := bytes.Repeat([]byte("APK acceptance fixture"), 8192)
	sum := sha256.Sum256(apk)
	m := Manifest{Version: "0.1.170", VersionCode: 2073270, Package: "kapi.ledger", Filename: "guizhang-android-0.1.170.apk", Size: int64(len(apk)), SHA256: hex.EncodeToString(sum[:]), Certificate: strings.Repeat("a", 64), GitHubURL: GitHubReleases + "/download/android-v0.1.170/guizhang-android-0.1.170.apk"}
	if err := os.WriteFile(filepath.Join(dir, m.Filename), apk, 0600); err != nil {
		t.Fatal(err)
	}
	sign(t, dir, m, key)
	return dir, pub, apk, m, key
}
func sign(t *testing.T, dir string, m Manifest, key *ecdsa.PrivateKey) {
	t.Helper()
	payload, _ := json.Marshal(m)
	sum := sha256.Sum256(append([]byte(SignatureContext), payload...))
	sig, _ := ecdsa.SignASN1(rand.Reader, key, sum[:])
	b, _ := json.Marshal(signedManifest{payload, base64.RawURLEncoding.EncodeToString(sig)})
	if err := os.WriteFile(filepath.Join(dir, "release.json"), b, 0600); err != nil {
		t.Fatal(err)
	}
}
func server(h *Handler) http.Handler { m := http.NewServeMux(); h.Register(m); return m }
func TestSignedDownloadWithoutLedgerCredentialsAndRangeResume(t *testing.T) {
	dir, pub, apk, m, _ := fixture(t)
	s := server(load(dir, pub))
	for _, path := range []string{"/android/", "/v1/android/release"} {
		w := httptest.NewRecorder()
		s.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		if w.Code != 200 || !strings.Contains(w.Body.String(), m.SHA256) {
			t.Fatal(path, w.Code)
		}
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, httptest.NewRequest("GET", "/android/apk", nil))
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), apk) || !strings.Contains(w.Header().Get("Content-Disposition"), m.Filename) {
		t.Fatal("APK download")
	}
	req := httptest.NewRequest("GET", "/android/apk", nil)
	req.Header.Set("Range", "bytes=100-199")
	w = httptest.NewRecorder()
	s.ServeHTTP(w, req)
	if w.Code != 206 || !bytes.Equal(w.Body.Bytes(), apk[100:200]) {
		t.Fatal("resume", w.Code)
	}
	w = httptest.NewRecorder()
	s.ServeHTTP(w, httptest.NewRequest("HEAD", "/android/apk", nil))
	if w.Code != 200 || w.Body.Len() != 0 {
		t.Fatal("HEAD")
	}
}
func TestWrongPublisherAndModifiedManifestNeverExposeAPK(t *testing.T) {
	dir, pub, _, m, key := fixture(t)
	other, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	sign(t, dir, m, other)
	h := load(dir, pub)
	if h.manifest != nil {
		t.Fatal("untrusted issuer")
	}
	sign(t, dir, m, key)
	b, _ := os.ReadFile(filepath.Join(dir, "release.json"))
	b = bytes.ReplaceAll(b, []byte("0.1.170"), []byte("0.1.171"))
	os.WriteFile(filepath.Join(dir, "release.json"), b, 0600)
	if load(dir, pub).manifest != nil {
		t.Fatal("modified manifest")
	}
	w := httptest.NewRecorder()
	server(h).ServeHTTP(w, httptest.NewRequest("GET", "/android/apk", nil))
	if w.Code != 503 {
		t.Fatal(w.Code)
	}
}
func TestArtifactChangedAfterStartupIsRejected(t *testing.T) {
	dir, pub, apk, m, _ := fixture(t)
	h := load(dir, pub)
	apk[20] ^= 1
	os.WriteFile(filepath.Join(dir, m.Filename), apk, 0600)
	w := httptest.NewRecorder()
	server(h).ServeHTTP(w, httptest.NewRequest("GET", "/android/apk", nil))
	if w.Code != 503 || bytes.Contains(w.Body.Bytes(), apk[:20]) {
		t.Fatal("corrupt download")
	}
}
func TestTraversalSymlinkAndBadIdentityFailClosedWithoutStoppingNAS(t *testing.T) {
	for _, kind := range []string{"path", "package", "host", "size", "symlink"} {
		t.Run(kind, func(t *testing.T) {
			dir, pub, _, m, key := fixture(t)
			switch kind {
			case "path":
				m.Filename = "../orders.json"
			case "package":
				m.Package = "malicious.app"
			case "host":
				m.GitHubURL = "https://example.com/app.apk"
			case "size":
				m.Size = 1
			case "symlink":
				os.Rename(filepath.Join(dir, m.Filename), filepath.Join(dir, "other.apk"))
				os.Symlink(filepath.Join(dir, "other.apk"), filepath.Join(dir, m.Filename))
			}
			sign(t, dir, m, key)
			h := load(dir, pub)
			if h.manifest != nil {
				t.Fatal("invalid release accepted")
			}
			w := httptest.NewRecorder()
			server(h).ServeHTTP(w, httptest.NewRequest("GET", "/android/", nil))
			if w.Code != 200 || !strings.Contains(w.Body.String(), GitHubReleases) {
				t.Fatal("fallback")
			}
		})
	}
}
func TestOnlyReadMethodsAndNoArbitraryFileAccess(t *testing.T) {
	dir, pub, _, _, _ := fixture(t)
	s := server(load(dir, pub))
	for _, p := range []string{"/android/", "/android/apk", "/v1/android/release"} {
		w := httptest.NewRecorder()
		s.ServeHTTP(w, httptest.NewRequest("POST", p, strings.NewReader("{}")))
		if w.Code != 405 {
			t.Fatal(p, w.Code)
		}
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, httptest.NewRequest("GET", "/android/orders.json", nil))
	if w.Code != 404 {
		t.Fatal(w.Code)
	}
	httpServer := httptest.NewServer(s)
	defer httpServer.Close()
	response, err := http.Get(httpServer.URL + "/android/apk?file=../../private.pem")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(response.Body)
	if response.StatusCode != 200 || len(body) == 0 {
		t.Fatal("fixed artifact only")
	}
}
