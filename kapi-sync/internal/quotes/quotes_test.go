package quotes

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

func TestNetAssetPriceIsDecimalAndDated(t *testing.T) {
	b := []byte(`var Data_netWorthTrend=[{"x":1791388800000,"y":2.761},{"x":1791302400000,"y":2.7}];`)
	q, e := ParseFund("FUND:110022", b, "fixture")
	if e != nil || q.Price != "2.761" || q.Date != "2026-10-08" {
		t.Fatalf("%+v %v", q, e)
	}
	for _, bad := range []string{`var Data_netWorthTrend=[];`, `var Data_netWorthTrend=[{"x":1791388800000,"y":"NaN"}];`, `alert('not data')`} {
		if _, e = ParseFund("FUND:110022", []byte(bad), "fixture"); e == nil {
			t.Fatal("accepted malformed quote")
		}
	}
}

type fakeProvider struct{ fail bool }

func (f *fakeProvider) Name() string { return "fixture" }
func (f *fakeProvider) Fetch(ctx context.Context, s string) (Quote, error) {
	if f.fail {
		return Quote{}, fmt.Errorf("fixture offline")
	}
	return Quote{Symbol: s, Price: "2.761", Date: "2026-10-08", Source: "fixture"}, nil
}
func TestFailurePreservesDatedPriceAndCacheRestores(t *testing.T) {
	path := filepath.Join(t.TempDir(), "quotes.json")
	s, e := New(path, "", "")
	if e != nil {
		t.Fatal(e)
	}
	s.provider = &fakeProvider{}
	if _, e = s.Get([]string{"FUND:110022"}, false); e != nil {
		t.Fatal(e)
	}
	s.refresh(context.Background(), true)
	s.provider = &fakeProvider{true}
	prior := s.items["FUND:110022"]
	prior.FetchedAt = time.Now().Add(-24 * time.Hour).UnixMilli()
	prior.AttemptedAt = prior.FetchedAt
	s.items[prior.Symbol] = prior
	s.refresh(context.Background(), true)
	q, e := s.Get([]string{"FUND:110022"}, false)
	if e != nil || q[0].Price != "2.761" || q[0].Date != "2026-10-08" || q[0].Error == "" {
		t.Fatalf("%+v %v", q, e)
	}
	restored, e := New(path, "", "")
	if e != nil || restored.items["FUND:110022"].Price != "2.761" {
		t.Fatal(e)
	}
	if _, e = s.Get([]string{"FUND:../../secret"}, false); e == nil {
		t.Fatal("invalid symbol accepted")
	}
}
func TestTushareExactDecimalAndReturnedFieldOrder(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"code":0,"data":{"fields":["unit_nav","nav_date"],"items":[[2.761,"20261008"],[2.7,"20261007"]]}}`)
	}))
	defer server.Close()
	transport := &rewriteTransport{target: server.URL}
	provider := &Tushare{client: &http.Client{Transport: transport, Timeout: time.Second}, token: "fixture"}
	q, e := provider.Fetch(context.Background(), "FUND:110022")
	if e != nil || q.Price != "2.761" || q.Date != "2026-10-08" {
		t.Fatalf("%+v %v", q, e)
	}
}

type rewriteTransport struct{ target string }

func (t *rewriteTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	req, _ := http.NewRequestWithContext(r.Context(), r.Method, t.target, r.Body)
	req.Header = r.Header
	return http.DefaultTransport.RoundTrip(req)
}

func TestStockCloseIsUnadjustedExactAndDated(t *testing.T) {
	q, e := ParseStock("SH:600519", []byte(`{"rc":0,"data":{"klines":["2026-10-07,1200.00,1250.29,1300.00","2026-10-08,1255.42,1263.00,1282.00"]}}`), "fixture")
	if e != nil || q.Price != "1263.00" || q.Date != "2026-10-08" {
		t.Fatalf("%+v %v", q, e)
	}
}
