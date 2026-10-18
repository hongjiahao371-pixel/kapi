package quotes

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func response(body string) *http.Response {
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header)}
}
func yesterday() string { return marketTime(time.Now()).AddDate(0, 0, -1).Format("2006-01-02") }
func rawStock(key, date, price string) string {
	return fmt.Sprintf(`{"code":0,"data":{"%s":{"day":[["%s","1","%s"]],"qfqday":[["%s","1","999"]]}}}`, key, date, price, date)
}
func TestTencentExactUnadjustedStocksAndETF(t *testing.T) {
	for _, symbol := range []string{"SH:600519", "SZ:000001", "SH:510300", "SZ:159915"} {
		t.Run(symbol, func(t *testing.T) {
			key := strings.ToLower(strings.ReplaceAll(symbol, ":", ""))
			q, e := ParseTencentStock(symbol, []byte(rawStock(key, yesterday(), "1.02345678")), "fixture")
			if e != nil || q.Price != "1.02345678" || q.Symbol != symbol || q.Date != yesterday() {
				t.Fatalf("%+v %v", q, e)
			}
		})
	}
	for _, body := range []string{`{"code":0,"data":{"sh600519":{"qfqday":[["2026-10-08","1","100"]]}}}`, rawStock("sz600519", yesterday(), "1"), rawStock("sh600519", yesterday(), "0"), rawStock("sh600519", yesterday(), "NaN"), rawStock("sh600519", "2999-01-01", "1"), `{"code":1}`, `alert('x')`} {
		if _, e := ParseTencentStock("SH:600519", []byte(body), "fixture"); e == nil {
			t.Fatalf("accepted %s", body)
		}
	}
}
func TestPrimaryDisconnectUsesIndependentRawFallback(t *testing.T) {
	calls := []string{}
	p := &Eastmoney{client: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls = append(calls, r.URL.Host)
		if r.URL.Host == "push2his.eastmoney.com" {
			return nil, errors.New("peer closed connection")
		}
		if r.URL.Host != "web.ifzq.gtimg.cn" || r.URL.Query().Get("param") != "sh600519,day,,,5," {
			t.Fatalf("wrong fallback %s", r.URL)
		}
		return response(rawStock("sh600519", yesterday(), "1263.000")), nil
	})}}
	q, e := p.Fetch(context.Background(), "SH:600519")
	if e != nil || q.Price != "1263.000" || !strings.Contains(q.Source, "腾讯") || len(calls) != 2 {
		t.Fatalf("%+v %v %v", q, e, calls)
	}
}
func TestPrimaryTimeoutReservesFallbackBudget(t *testing.T) {
	p := &Eastmoney{client: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host == "push2his.eastmoney.com" {
			deadline, ok := r.Context().Deadline()
			if !ok || time.Until(deadline) > 3100*time.Millisecond {
				t.Fatal("primary consumes full budget")
			}
			<-r.Context().Done()
			return nil, r.Context().Err()
		}
		return response(rawStock("sz000001", yesterday(), "10.23")), nil
	})}}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	q, e := p.Fetch(ctx, "SZ:000001")
	if e != nil || q.Price != "10.23" {
		t.Fatalf("%+v %v", q, e)
	}
}
func at(s string) time.Time {
	v, e := time.Parse(time.RFC3339, s)
	if e != nil {
		panic(e)
	}
	return v
}
func TestAutomaticScheduleMarketTimezoneAndDelayedPublication(t *testing.T) {
	cases := []struct {
		name, symbol, date, last, now string
		want                          bool
	}{
		{"stock before close", "SH:600519", "2026-10-08", "2026-10-09T06:00:00Z", "2026-10-09T07:14:00Z", false},
		{"stock after close UTC", "SH:600519", "2026-10-08", "2026-10-09T06:00:00Z", "2026-10-09T07:15:00Z", true},
		{"delayed candle retry", "SH:600519", "2026-10-08", "2026-10-09T07:16:00Z", "2026-10-09T07:31:00Z", true},
		{"current no repeated calls", "SH:600519", "2026-10-09", "2026-10-09T07:16:00Z", "2026-10-09T09:31:00Z", false},
		{"holiday window ended", "SH:600519", "2026-10-08", "2026-10-09T07:16:00Z", "2026-10-09T10:31:00Z", false},
		{"fund before publication", "FUND:110022", "2026-10-08", "2026-10-09T06:00:00Z", "2026-10-09T12:59:00Z", false},
		{"fund evening publication", "FUND:110022", "2026-10-08", "2026-10-09T06:00:00Z", "2026-10-09T13:00:00Z", true},
		{"new date", "SZ:000001", "2026-10-09", "2026-10-09T08:00:00Z", "2026-10-10T01:00:00Z", true}}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			q := Quote{Symbol: c.symbol, Date: c.date, AttemptedAt: at(c.last).UnixMilli()}
			if due(q, at(c.now), false) != c.want {
				t.Fatalf("unexpected schedule %+v", q)
			}
		})
	}
	if closedSession("2026-10-09", at("2026-10-09T07:14:00Z")) || !closedSession("2026-10-09", at("2026-10-09T07:15:00Z")) || !closedSession("2026-10-08", at("2026-10-09T02:00:00Z")) {
		t.Fatal("unfinished candle selection")
	}
}

type controlledProvider struct {
	calls atomic.Int32
	fail  bool
	date  string
	block chan struct{}
}

func (p *controlledProvider) Name() string { return "fixture" }
func (p *controlledProvider) Fetch(ctx context.Context, symbol string) (Quote, error) {
	p.calls.Add(1)
	if p.block != nil {
		select {
		case <-p.block:
		case <-ctx.Done():
			return Quote{}, ctx.Err()
		}
	}
	if p.fail {
		return Quote{}, errors.New("offline")
	}
	date := p.date
	if date == "" {
		date = yesterday()
	}
	return Quote{Symbol: symbol, Price: "12.3400", Date: date, Source: "fixture"}, nil
}
func newControlled(t *testing.T) (*Service, *controlledProvider) {
	t.Helper()
	s, e := New(filepath.Join(t.TempDir(), "quotes.json"), "", "")
	if e != nil {
		t.Fatal(e)
	}
	p := &controlledProvider{}
	s.provider = p
	return s, p
}
func TestRefreshAwaitsSelectedResultsAndKeepsSuccessfulTimestamp(t *testing.T) {
	s, p := newControlled(t)
	now := time.Now()
	s.now = func() time.Time { return now }
	s.get([]string{"SZ:000001"}, false, false)
	rows, e := s.Refresh(context.Background(), []string{"SH:600519"})
	if e != nil || rows[0].Price != "12.3400" || p.calls.Load() != 1 {
		t.Fatalf("%+v %v", rows, e)
	}
	original := rows[0]
	p.fail = true
	now = now.Add(16 * time.Minute)
	rows, e = s.Refresh(context.Background(), []string{"SH:600519"})
	q := rows[0]
	if e != nil || q.Price != original.Price || q.Date != original.Date || q.FetchedAt != original.FetchedAt || q.AttemptedAt == original.AttemptedAt || q.Error == "" {
		t.Fatalf("failure replaced quote %+v", q)
	}
	if due(q, now.Add(29*time.Second), true) || !due(q, now.Add(30*time.Second), true) || due(q, now.Add(4*time.Minute), false) || !due(q, now.Add(5*time.Minute), false) {
		t.Fatal("failure backoff")
	}
	restored, e := New(s.path, "", "")
	if e != nil || restored.items[q.Symbol].AttemptedAt != q.AttemptedAt || restored.items[q.Symbol].FetchedAt != q.FetchedAt {
		t.Fatalf("restart lost timestamps %v", e)
	}
	p.fail = false
	p.date = marketTime(time.Now()).AddDate(0, 0, -2).Format("2006-01-02")
	now = now.Add(time.Minute)
	rows, e = s.Refresh(context.Background(), []string{q.Symbol})
	if e != nil || rows[0].Date != original.Date || !strings.Contains(rows[0].Error, "早于") {
		t.Fatalf("older provider overwrote cache %+v %v", rows, e)
	}
}
func TestConcurrentRefreshSharesAttemptAndHonorsCancellation(t *testing.T) {
	s, p := newControlled(t)
	p.block = make(chan struct{})
	done := make(chan error, 1)
	go func() { _, e := s.Refresh(context.Background(), []string{"SH:600519"}); done <- e }()
	limit := time.After(time.Second)
	for p.calls.Load() == 0 {
		select {
		case <-limit:
			t.Fatal("request never started")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, e := s.Refresh(ctx, []string{"SH:600519"}); !errors.Is(e, context.DeadlineExceeded) {
		t.Fatalf("cancel ignored: %v", e)
	}
	close(p.block)
	if e := <-done; e != nil {
		t.Fatal(e)
	}
	if _, e := s.Refresh(context.Background(), []string{"SH:600519"}); e != nil || p.calls.Load() != 1 {
		t.Fatalf("repeated attempt %d %v", p.calls.Load(), e)
	}
}

func TestBackgroundRegistrationUpdatesWithoutPhonePolling(t *testing.T) {
	s, p := newControlled(t)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { s.Run(ctx); close(done) }()
	defer func() { cancel(); <-done }()
	rows, e := s.Get([]string{"SZ:000001"}, false)
	if e != nil || len(rows) != 1 {
		t.Fatal(e)
	}
	deadline := time.After(time.Second)
	for {
		rows, e = s.get([]string{"SZ:000001"}, false, false)
		if e != nil {
			t.Fatal(e)
		}
		if rows[0].Price == "12.3400" {
			break
		}
		select {
		case <-deadline:
			t.Fatal("first registration was never refreshed by NAS background worker")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	if p.calls.Load() != 1 || rows[0].Date != yesterday() || rows[0].FetchedAt == 0 {
		t.Fatalf("background result %+v", rows)
	}
}
