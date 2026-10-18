package shortcutweb

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestInstallPageContainsOnlyTemplatesAndKnownDownloads(t *testing.T) {
	m := http.NewServeMux()
	Register(m)
	for _, p := range []string{"/shortcuts/", "/shortcuts/files/setup.shortcut", "/shortcuts/files/capture.shortcut", "/shortcuts/files/manual.shortcut", "/shortcuts/files/resume.shortcut", "/shortcuts/files/sync.shortcut"} {
		w := httptest.NewRecorder()
		m.ServeHTTP(w, httptest.NewRequest("GET", p, nil))
		if w.Code != 200 || w.Body.Len() < 500 {
			t.Fatal(p, w.Code, w.Body.Len())
		}
		if !strings.Contains(w.Header().Get("Content-Security-Policy"), "default-src 'none'") {
			t.Fatal("missing CSP")
		}
	}
	for _, p := range []string{"/shortcuts/files/meta.json", "/shortcuts/files/../../meta.json", "/shortcuts/unknown"} {
		w := httptest.NewRecorder()
		m.ServeHTTP(w, httptest.NewRequest("GET", p, nil))
		if w.Code == 200 {
			t.Fatal("unknown path exposed", p)
		}
	}
}
func TestStaticDownloadRejectsWritesAndHeadContainsNoBody(t *testing.T) {
	m := http.NewServeMux()
	Register(m)
	w := httptest.NewRecorder()
	m.ServeHTTP(w, httptest.NewRequest("POST", "/shortcuts/files/setup.shortcut", nil))
	if w.Code != 405 {
		t.Fatal(w.Code)
	}
	w = httptest.NewRecorder()
	m.ServeHTTP(w, httptest.NewRequest("HEAD", "/shortcuts/files/capture.shortcut", nil))
	if w.Code != 200 || w.Body.Len() != 0 || !strings.Contains(w.Header().Get("Content-Disposition"), "filename*=") {
		t.Fatal(w.Code, w.Header(), w.Body.Len())
	}
}
