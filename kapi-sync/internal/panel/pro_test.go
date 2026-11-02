package panel_test

import (
	"bytes"
	"encoding/json"
	"kapi/sync/internal/api"
	"kapi/sync/internal/family"
	"kapi/sync/internal/panel"
	"kapi/sync/internal/store"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func TestFreeBoundaryAuthActivationBackupAndFamilyCreation(t *testing.T) {
	dir := t.TempDir()
	s, e := store.Open(dir)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	h, _ := panel.New(s, "panel-secret")
	mux := http.NewServeMux()
	h.Register(mux)
	f, e := family.Open(s, dir, "panel-secret", func(st *store.Store) http.Handler { return api.New(st) })
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	f.Register(mux)
	call := func(method, path, token, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		r.Header.Set("X-Panel-Token", token)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, r)
		return w
	}
	if w := call("GET", "/v1/panel/pro", "", ""); w.Code != 401 {
		t.Fatal(w.Code)
	}
	if w := call("POST", "/v1/panel/pro/activate", "panel-secret", `{"code":"bad","enabled":true}`); w.Code != 400 {
		t.Fatal(w.Code)
	}
	if w := call("POST", "/v1/panel/pro/activate", "panel-secret", `{"code":"bad"}`); w.Code != 400 || s.Pro.Enabled() {
		t.Fatal(w.Code)
	}
	if w := call("POST", "/v1/family/enable", "panel-secret", `{}`); w.Code != 403 {
		t.Fatal("free creation", w.Code)
	}
	if w := call("GET", "/v1/panel/summary", "panel-secret", ""); w.Code != 200 {
		t.Fatal("free summary", w.Code)
	}
	if w := call("GET", "/v1/panel/assets", "panel-secret", ""); w.Code != 200 {
		t.Fatal("free assets", w.Code)
	}
	var v map[string]any
	w := call("GET", "/v1/panel/pro", "panel-secret", "")
	if json.Unmarshal(w.Body.Bytes(), &v) != nil || v["enabled"] != false || v["payments_enabled"] != false {
		t.Fatal(w.Body.String())
	}
	archive, e := s.CreateBackup(true)
	if e != nil {
		t.Fatal(e)
	}
	restored := filepath.Join(t.TempDir(), "restored")
	if e = store.RestoreBackup(archive, restored); e != nil {
		t.Fatal(e)
	}
	again, e := store.Open(restored)
	if e != nil {
		t.Fatal(e)
	}
	defer again.Close()
	if again.Pro.Enabled() {
		t.Fatal("free backup restore became Pro")
	}
}
