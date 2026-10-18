package api_test

import (
	"encoding/json"
	"fmt"
	"io"
	"kapi/sync/internal/api"
	"kapi/sync/internal/quotes"
	"kapi/sync/internal/store"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestQuotesRequireDeviceAuthorizationAndDoNotWriteLedger(t *testing.T) {
	st, e := store.Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	defer st.Close()
	q, e := quotes.New(filepath.Join(t.TempDir(), "quotes.json"), "", "")
	if e != nil {
		t.Fatal(e)
	}
	srv := api.New(st)
	srv.EnableQuotes(q)
	req := httptest.NewRequest("GET", "/v1/investment/quotes?symbols=FUND:110022", nil)
	rr := httptest.NewRecorder()
	srv.ServeHTTP(rr, req)
	if rr.Code != 401 {
		t.Fatal(rr.Code)
	}
	code, _ := st.PairCode()
	d, e := st.Pair(code, "quotes-test")
	if e != nil {
		t.Fatal(e)
	}
	before := st.HealthInfo()
	for _, path := range []string{"/v1/investment/quotes?symbols=FUND:110022", "/v1/investment/quotes?symbols=SH:600519", "/v1/investment/quotes?symbols=bad"} {
		req = httptest.NewRequest("GET", path, nil)
		req.Header.Set("Authorization", "Bearer "+d.Token)
		rr = httptest.NewRecorder()
		srv.ServeHTTP(rr, req)
		expected := 200
		if path == "/v1/investment/quotes?symbols=bad" {
			expected = 400
		}
		if rr.Code != expected {
			t.Fatalf("%s: %d", path, rr.Code)
		}
	}
	after := st.HealthInfo()
	if before.Rows != after.Rows || before.DBSeq != after.DBSeq {
		t.Fatal("quote cache altered ledger")
	}
}

// A POST must return the completed result; no GET-registration worker should race it.
func TestPostReturnsCompletedQuoteInTheSameResponse(t *testing.T) {
	old := http.DefaultTransport
	defer func() { http.DefaultTransport = old }()
	calls := 0
	date := time.Now().AddDate(0, 0, -1).Format("2006-01-02")
	http.DefaultTransport = quoteTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		body := fmt.Sprintf(`{"rc":0,"data":{"klines":["%s,1200,1263.000,1300"]}}`, date)
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header)}, nil
	})
	st, e := store.Open(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	defer st.Close()
	q, e := quotes.New(filepath.Join(t.TempDir(), "quotes.json"), "eastmoney-personal", "")
	if e != nil {
		t.Fatal(e)
	}
	srv := api.New(st)
	srv.EnableQuotes(q)
	code, _ := st.PairCode()
	device, e := st.Pair(code, "post-quote-test")
	if e != nil {
		t.Fatal(e)
	}
	before := st.HealthInfo()
	req := httptest.NewRequest("POST", "/v1/investment/quotes?symbols=SH:600519", nil)
	req.Header.Set("Authorization", "Bearer "+device.Token)
	rr := httptest.NewRecorder()
	srv.ServeHTTP(rr, req)
	var got struct {
		Quotes []quotes.Quote `json:"quotes"`
	}
	json.Unmarshal(rr.Body.Bytes(), &got)
	if rr.Code != 200 || len(got.Quotes) != 1 || got.Quotes[0].Price != "1263.000" || got.Quotes[0].Error != "" || got.Quotes[0].FetchedAt == 0 || calls != 1 {
		t.Fatalf("unfinished POST: %d %s calls=%d", rr.Code, rr.Body, calls)
	}
	after := st.HealthInfo()
	if before.Rows != after.Rows || before.DBSeq != after.DBSeq {
		t.Fatal("quotes changed financial ledger")
	}
}

type quoteTransport func(*http.Request) (*http.Response, error)

func (f quoteTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
