package panel

import (
	"kapi/sync/internal/store"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestAssetsEndpointRequiresAuthIsReadOnlyAndReturnsEmptyArrays(t *testing.T) {
	s, e := store.Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	h, _ := New(s, "asset-token")
	mux := http.NewServeMux()
	h.Register(mux)
	for _, test := range []struct {
		method, token string
		want          int
	}{{"GET", "", 401}, {"GET", "wrong", 401}, {"POST", "asset-token", 405}, {"GET", "asset-token", 200}} {
		before := s.HealthInfo()
		r := httptest.NewRequest(test.method, "/v1/panel/assets", nil)
		r.Header.Set("X-Panel-Token", test.token)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, r)
		if w.Code != test.want {
			t.Fatal(test, w.Code)
		}
		if s.HealthInfo() != before {
			t.Fatal("panel mutated sync state")
		}
		if w.Code == 200 {
			for _, field := range []string{`"accounts":[]`, `"positions":[]`, `"trades":[]`} {
				if !strings.Contains(w.Body.String(), field) {
					t.Fatal(w.Body.String())
				}
			}
		}
	}
}
