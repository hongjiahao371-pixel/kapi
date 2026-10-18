package panel

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"kapi/sync/internal/store"
)

func TestPanelEntryRedirectPreservesUnknownRoutesAndAuth(t *testing.T) {
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h, _ := New(st, "entry-test-token")
	mux := http.NewServeMux()
	h.Register(mux)
	for _, tc := range []struct {
		method, path string
		status       int
	}{
		{"GET", "/", http.StatusFound},
		{"HEAD", "/", http.StatusFound},
		{"GET", "/not-a-route", http.StatusNotFound},
		{"POST", "/", http.StatusMethodNotAllowed},
		{"GET", "/v1/panel/summary", http.StatusUnauthorized},
	} {
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, httptest.NewRequest(tc.method, tc.path, nil))
		if rr.Code != tc.status {
			t.Fatalf("%s %s: got %d, want %d", tc.method, tc.path, rr.Code, tc.status)
		}
		if tc.status == http.StatusFound && rr.Header().Get("Location") != "/panel/" {
			t.Fatalf("wrong entry target: %s", rr.Header().Get("Location"))
		}
	}
}

// 配对码端点挂的是面板令牌（浏览器用），不是设备 token——这条链路是
// "手机丢了也能在面板上重新配对" 的前提，锁死它。
func TestPairCodeRequiresPanelToken(t *testing.T) {
	dir := filepath.Join(t.TempDir())
	st, err := store.Open(dir)
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	h, token := New(st, "test-panel-token")

	mux := http.NewServeMux()
	h.Register(mux)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	// 无令牌 → 401。配对码能换设备的写权限，不能裸奔。
	resp, err := http.Get(srv.URL + "/v1/panel/pair_code")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无令牌应 401，得到 %d", resp.StatusCode)
	}

	// 带面板令牌 → 200 + 6 位数字码。
	req, _ := http.NewRequest("GET", srv.URL+"/v1/panel/pair_code", nil)
	req.Header.Set("X-Panel-Token", token)
	resp2, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get with token: %v", err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("带令牌应 200，得到 %d", resp2.StatusCode)
	}
	var out struct {
		PairCode string `json:"pair_code"`
	}
	if err := json.NewDecoder(resp2.Body).Decode(&out); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(out.PairCode) != 6 {
		t.Fatalf("配对码应为 6 位，得到 %q", out.PairCode)
	}
	for _, c := range out.PairCode {
		if c < '0' || c > '9' {
			t.Fatalf("配对码应为纯数字，得到 %q", out.PairCode)
		}
	}
}

func TestPanelCSPAuthorizesExactShippedScriptAndBlocksURLCredentials(t *testing.T) {
	st, e := store.Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	defer st.Close()
	h, _ := New(st, "test-credential")
	mux := http.NewServeMux()
	h.Register(mux)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, httptest.NewRequest("GET", "/panel/", nil))
	body := w.Body.String()
	start := strings.Index(body, "<script>")
	end := strings.Index(body, "</script>")
	if start < 0 || end <= start {
		t.Fatal("missing inline script")
	}
	sum := sha256.Sum256([]byte(body[start+len("<script>") : end]))
	expected := "'sha256-" + base64.StdEncoding.EncodeToString(sum[:]) + "'"
	if !strings.Contains(w.Header().Get("Content-Security-Policy"), expected) || strings.Contains(w.Header().Get("Content-Security-Policy"), "script-src 'unsafe-inline'") {
		t.Fatal("CSP breaks or over-trusts script")
	}
	if !strings.Contains(body, "sessionStorage.setItem(LS,S.token)") || strings.Contains(body, "localStorage.setItem(LS,S.token)") {
		t.Fatal("credential persists beyond browser session")
	}
	w = httptest.NewRecorder()
	mux.ServeHTTP(w, httptest.NewRequest("GET", "/v1/panel/summary?token=test-credential", nil))
	if w.Code != 401 {
		t.Fatal("URL credential accepted")
	}
}
