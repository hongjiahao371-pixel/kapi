package api_test

import (
	"encoding/json"
	"kapi/sync/internal/api"
	"kapi/sync/internal/panel"
	"kapi/sync/internal/store"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHealthNeverDisclosesPairingCodeAndAuthorizedPairingStillWorks(t *testing.T) {
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	srv := api.New(st)
	ph, _ := panel.New(st, "owner-panel-token")
	srv.Mount(ph.Register)
	for _, path := range []string{"/v1/health", "/v1/health?pair_code=1"} {
		for _, token := range []string{"", "invalid", "owner-panel-token"} {
			req := httptest.NewRequest("GET", path, nil)
			req.Header.Set("X-Panel-Token", token)
			rr := httptest.NewRecorder()
			srv.ServeHTTP(rr, req)
			if rr.Code != http.StatusOK {
				t.Fatalf("public health rejected: %d", rr.Code)
			}
			var health map[string]json.RawMessage
			if err := json.Unmarshal(rr.Body.Bytes(), &health); err != nil {
				t.Fatal(err)
			}
			if _, ok := health["pair_code"]; ok {
				t.Fatal("public health disclosed a pairing credential")
			}
		}
	}
	for _, path := range []string{"/v1/changes", "/v1/row?entity=txn&id=x", "/v1/backup", "/v1/backups", "/v1/pair/code", "/v1/panel/pair_code", "/v1/panel/backup", "/v1/panel/backups"} {
		rr := httptest.NewRecorder()
		srv.ServeHTTP(rr, httptest.NewRequest("GET", path, nil))
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("%s allowed unauthenticated access: %d", path, rr.Code)
		}
	}
	req := httptest.NewRequest("GET", "/v1/panel/pair_code", nil)
	req.Header.Set("X-Panel-Token", "owner-panel-token")
	rr := httptest.NewRecorder()
	srv.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatal(rr.Code)
	}
	var code struct {
		Code string `json:"pair_code"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &code); err != nil {
		t.Fatal(err)
	}
	if len(code.Code) != 6 {
		t.Fatal("owner could not get a pairing code")
	}
	req = httptest.NewRequest("POST", "/v1/pair", strings.NewReader(`{"pair_code":"`+code.Code+`","device_name":"acceptance"}`))
	rr = httptest.NewRecorder()
	srv.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatal(rr.Code)
	}
	var pair struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &pair); err != nil {
		t.Fatal(err)
	}
	req = httptest.NewRequest("GET", "/v1/changes", nil)
	req.Header.Set("Authorization", "Bearer "+pair.Token)
	rr = httptest.NewRecorder()
	srv.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatal("paired phone lost sync access")
	}
}
