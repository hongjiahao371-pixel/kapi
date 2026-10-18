package panel

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestFieldAIProtocolsAndNoRedirectKeyLeak(t *testing.T) {
	for _, kind := range []string{"chat", "responses", "claude", "gemini"} {
		t.Run(kind, func(t *testing.T) {
			path := "/v1"
			wire := `{"choices":[{"message":{"content":"{\"mapping\":{\"TIME\":0,\"AMOUNT\":1}}"}}]}`
			switch kind {
			case "responses":
				path = "/v1/responses"
				wire = `{"output":[{"content":[{"text":"ok"}]}]}`
			case "claude":
				path = "/v1/messages"
				wire = `{"content":[{"text":"ok"}]}`
			case "gemini":
				path = "/v1beta/models/test:generateContent"
				wire = `{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}`
			}
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				b, _ := io.ReadAll(r.Body)
				if !strings.Contains(string(b), "only-shapes") {
					t.Error("wrong payload")
				}
				if r.Method != "POST" {
					t.Error("not post")
				}
				fmt.Fprint(w, wire)
			}))
			defer srv.Close()
			raw, e := requestImportFields(context.Background(), formatRequest{BaseURL: srv.URL + path, Model: "test", APIKey: "test-secret"}, "only-shapes")
			if e != nil || raw == "" {
				t.Fatal(raw, e)
			}
		})
	}
	hit := false
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hit = true }))
	defer target.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, target.URL, 307) }))
	defer redirect.Close()
	_, err := requestImportFields(context.Background(), formatRequest{BaseURL: redirect.URL, Model: "test", APIKey: "test-secret"}, "headers")
	if err == nil || hit || strings.Contains(err.Error(), "test-secret") {
		t.Fatal("redirect or secret leakage")
	}
}
func TestFormatMappingNeedsReviewRevisionAndNeverWrites(t *testing.T) {
	if os.Getenv("KAPI_IMPORT_JAR") == "" {
		t.Skip("real core required")
	}
	st, _, mux := importFixture(t)
	reviewSeed(t, st)
	p := importUpload(t, mux, []byte("发生日,流向,发生额,附言\n2026-10-10,支出,12.35,秘密商户"))
	before := st.HealthInfo()
	if len(p.Candidates) != 0 {
		t.Fatal("unknown format guessed")
	}
	plan := reviewPost(mux, "/v1/panel/import/format-plan", map[string]any{"ticket": p.Ticket, "revision": p.Revision})
	if plan.Code != 200 || strings.Contains(plan.Body.String(), "秘密商户") || strings.Contains(plan.Body.String(), "12.35") {
		t.Fatal(plan.Body.String())
	}
	for _, mapping := range []map[string]int{{"TIME": 0, "AMOUNT": 0}, {"TIME": 0, "AMOUNT": 99}, {"TIME": 0, "AMOUNT": 2, "NATURE": 1}} {
		rr := reviewPost(mux, "/v1/panel/import/format-apply", map[string]any{"ticket": p.Ticket, "revision": p.Revision, "mapping": mapping})
		if rr.Code != 400 || st.HealthInfo() != before {
			t.Fatal("bad mapping accepted", rr.Body.String())
		}
	}
	body := map[string]any{"ticket": p.Ticket, "revision": p.Revision, "mapping": map[string]int{"TIME": 0, "AMOUNT": 2, "DIRECTION": 1, "NOTE": 3}}
	rr := reviewPost(mux, "/v1/panel/import/format-apply", body)
	if rr.Code != 200 {
		t.Fatal(rr.Body.String())
	}
	var next importPreview
	json.Unmarshal(rr.Body.Bytes(), &next)
	if next.Revision != p.Revision+1 || len(next.Candidates) != 1 || next.Candidates[0].Row.AmountMinor != 1235 || st.HealthInfo() != before {
		t.Fatal("preview wrote or changed money")
	}
	if reviewPost(mux, "/v1/panel/import/format-apply", body).Code != 409 {
		t.Fatal("stale revision accepted")
	}
	committed := reviewPost(mux, "/v1/panel/import/commit", map[string]any{"ticket": next.Ticket, "revision": next.Revision, "selected": []int{0}, "fallbackAccountId": "A1", "allowUnclassified": true, "acknowledgeIssues": true, "rememberMappings": true})
	if committed.Code != 200 {
		t.Fatal(committed.Body.String())
	}
	again := importUpload(t, mux, []byte("发生日,流向,发生额,附言\n2026-10-10,支出,12.35,秘密商户"))
	if len(again.Candidates) != 1 || again.Candidates[0].Match == "NEW" {
		t.Fatal("format not remembered or repeat treated as new", again)
	}
}
