package panel

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"kapi/sync/internal/store"
)

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
