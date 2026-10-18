package security_test

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"io"
	"kapi/sync/internal/api"
	"kapi/sync/internal/family"
	"kapi/sync/internal/panel"
	"kapi/sync/internal/security"
	"kapi/sync/internal/store"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSamePersonalAndFamilyAuthOverVerifiedHTTPSAndLegacyHTTP(t *testing.T) {
	dir := t.TempDir()
	st, e := store.Open(dir)
	if e != nil {
		t.Fatal(e)
	}
	defer st.Close()
	a := api.New(st)
	p, _ := panel.New(st, "test-panel-secret")
	a.Mount(p.Register)
	f, e := family.Open(st, dir, "test-panel-secret", func(s *store.Store) http.Handler { return api.New(s) })
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	a.Mount(f.Register)
	handler := security.NewGuard().Wrap(a)
	plain := httptest.NewServer(handler)
	defer plain.Close()
	encrypted := httptest.NewUnstartedServer(handler)
	encrypted.TLS = &tls.Config{MinVersion: tls.VersionTLS12}
	encrypted.StartTLS()
	defer encrypted.Close()
	pool := x509.NewCertPool()
	pool.AddCert(encrypted.Certificate())
	trusted := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}}}
	call := func(base, method, path, token string, body any) (int, map[string]any) {
		var b bytes.Buffer
		if body != nil {
			json.NewEncoder(&b).Encode(body)
		}
		r, _ := http.NewRequest(method, base+path, &b)
		if token == "test-panel-secret" {
			r.Header.Set("X-Panel-Token", token)
		} else if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		res, e := trusted.Do(r)
		if e != nil {
			t.Fatal(e)
		}
		defer res.Body.Close()
		var out map[string]any
		json.NewDecoder(res.Body).Decode(&out)
		return res.StatusCode, out
	}
	code, _ := st.PairCode()
	status, paired := call(plain.URL, "POST", "/v1/pair", "", map[string]string{"pair_code": code, "device_name": "legacy phone"})
	if status != 200 {
		t.Fatal(status, paired)
	}
	device := paired["token"].(string)
	for _, base := range []string{plain.URL, encrypted.URL} {
		status, _ = call(base, "GET", "/v1/changes", device, nil)
		if status != 200 {
			t.Fatal("old identity stopped", status)
		}
		status, _ = call(base, "GET", "/v1/panel/summary", "", nil)
		if status != 401 {
			t.Fatal("missing auth allowed")
		}
	}
	status, _ = call(encrypted.URL, "POST", "/v1/family/enable", "test-panel-secret", map[string]any{})
	if status != 200 {
		t.Fatal(status)
	}
	status, invite := call(encrypted.URL, "POST", "/v1/family/invite", "test-panel-secret", map[string]string{"name": "member"})
	if status != 200 {
		t.Fatal(status)
	}
	status, session := call(encrypted.URL, "POST", "/v1/family/join", "", map[string]any{"code": invite["code"], "device_name": "member phone", "protocol": "family-spaces-v1"})
	if status != 200 {
		t.Fatal(status, session)
	}
	personal := session["personal"].(map[string]any)
	shared := session["shared"].([]any)[0].(map[string]any)
	for _, base := range []string{plain.URL, encrypted.URL} {
		status, _ = call(base, "GET", personal["base_path"].(string)+"/v1/changes", personal["token"].(string), nil)
		if status != 200 {
			t.Fatal("private flow stopped", status)
		}
		status, _ = call(base, "GET", shared["base_path"].(string)+"/v1/changes", shared["token"].(string), nil)
		if status != 200 {
			t.Fatal("shared flow stopped", status)
		}
		status, _ = call(base, "GET", personal["base_path"].(string)+"/v1/changes", shared["token"].(string), nil)
		if status != 401 {
			t.Fatal("shared credential entered private space", status)
		}
	}
	status, _ = call(encrypted.URL, "POST", "/v1/family/join", "", map[string]any{"code": invite["code"], "protocol": "family-spaces-v1"})
	if status != 403 {
		t.Fatal("invite reused")
	}
	status, _ = call(encrypted.URL, "POST", "/v1/family/revoke", "test-panel-secret", map[string]any{"member_id": session["member"].(map[string]any)["id"]})
	if status != 200 {
		t.Fatal(status)
	}
	status, _ = call(encrypted.URL, "GET", shared["base_path"].(string)+"/v1/changes", shared["token"].(string), nil)
	if status != 403 {
		t.Fatal("revoked member retained access", status)
	}
	// Default trust must reject this private CA. No insecure trust fallback is configured.
	if res, e := http.Get(encrypted.URL + "/v1/health"); e == nil {
		res.Body.Close()
		t.Fatal("untrusted CA accepted")
	}
	old := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS10, MaxVersion: tls.VersionTLS11}}}
	if res, e := old.Get(encrypted.URL + "/v1/health"); e == nil {
		io.Copy(io.Discard, res.Body)
		res.Body.Close()
		t.Fatal("obsolete TLS accepted")
	}
}
