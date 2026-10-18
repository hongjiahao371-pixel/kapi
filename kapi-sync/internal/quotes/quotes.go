// Package quotes maintains an opt-in end-of-day quote cache. It never knows account balances or trades.
package quotes

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

type Quote struct {
	Symbol      string `json:"symbol"`
	Price       string `json:"price,omitempty"`
	Date        string `json:"date,omitempty"`
	Source      string `json:"source"`
	FetchedAt   int64  `json:"fetched_at"`
	AttemptedAt int64  `json:"last_attempt_at,omitempty"`
	Error       string `json:"error,omitempty"`
}
type Provider interface {
	Fetch(context.Context, string) (Quote, error)
	Name() string
}

// Snapshot reads existing prices without registering symbols or scheduling HTTP.
func (s *Service) Snapshot() map[string]Quote {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]Quote, len(s.items))
	for symbol, q := range s.items {
		out[symbol] = q
	}
	return out
}

type Service struct {
	mu       sync.Mutex
	items    map[string]Quote
	path     string
	provider Provider
	pending  chan bool
	client   *http.Client
	gate     chan struct{}
	now      func() time.Time
}

var symbolPattern = regexp.MustCompile(`^(FUND|SH|SZ):[0-9]{6}$`)

func Valid(s string) bool { return symbolPattern.MatchString(s) }
func New(path, mode, token string) (*Service, error) {
	s := &Service{items: map[string]Quote{}, path: path, pending: make(chan bool, 1), gate: make(chan struct{}, 1), now: time.Now, client: &http.Client{Timeout: 12 * time.Second}}
	switch mode {
	case "":
	case "eastmoney-personal":
		s.provider = &Eastmoney{s.client}
	case "tushare":
		if token == "" {
			return nil, errors.New("行情接口已选 Tushare，但缺少 NAS 端 API token")
		}
		s.provider = &Tushare{s.client, token}
	default:
		return nil, errors.New("不支持的行情接口配置")
	}
	if b, e := os.ReadFile(path); e == nil {
		if e = json.Unmarshal(b, &s.items); e != nil {
			return nil, fmt.Errorf("行情缓存损坏: %w", e)
		}
	} else if !os.IsNotExist(e) {
		return nil, e
	}
	for k := range s.items {
		if !Valid(k) {
			return nil, errors.New("行情缓存含无效代码")
		}
	}
	return s, nil
}
func (s *Service) saveLocked() error {
	b, e := json.Marshal(s.items)
	if e != nil {
		return e
	}
	if e = os.MkdirAll(filepath.Dir(s.path), 0700); e != nil {
		return e
	}
	f, e := os.CreateTemp(filepath.Dir(s.path), "quotes-*.tmp")
	if e != nil {
		return e
	}
	defer os.Remove(f.Name())
	if _, e = f.Write(b); e == nil {
		e = f.Sync()
	}
	ce := f.Close()
	if e == nil {
		e = ce
	}
	if e != nil {
		return e
	}
	return os.Rename(f.Name(), s.path)
}
func (s *Service) Get(symbols []string, refresh bool) ([]Quote, error) {
	return s.get(symbols, refresh, true)
}
func (s *Service) get(symbols []string, refresh, schedule bool) ([]Quote, error) {
	if len(symbols) > 50 {
		return nil, errors.New("一次最多请求 50 只资产")
	}
	for _, v := range symbols {
		if !Valid(v) {
			return nil, errors.New("行情代码格式无效")
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	changed := false
	for _, v := range symbols {
		if _, ok := s.items[v]; !ok {
			if len(s.items) >= 1000 {
				return nil, errors.New("行情关注列表已满")
			}
			s.items[v] = Quote{Symbol: v, Source: s.source(), Error: "等待更新"}
			changed = true
		}
	}
	if changed {
		if e := s.saveLocked(); e != nil {
			return nil, e
		}
	}
	if schedule && (changed || refresh) {
		select {
		case s.pending <- refresh:
		default:
		}
	}
	out := []Quote{}
	for _, v := range symbols {
		q := s.items[v]
		if s.provider == nil {
			q.Error = "NAS 尚未配置行情接口，持仓和手动净值仍可使用"
		}
		out = append(out, q)
	}
	return out, nil
}
func (s *Service) source() string {
	if s.provider == nil {
		return "未配置"
	}
	return s.provider.Name()
}

// Refresh returns the result of this attempt. GET remains a fast cache read.
// Per-request work is limited to the requested symbols, and concurrent attempts share a gate.
func (s *Service) Refresh(ctx context.Context, symbols []string) ([]Quote, error) {
	if _, err := s.get(symbols, false, false); err != nil {
		return nil, err
	}
	s.refreshSelected(ctx, true, symbols)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return s.get(symbols, false, false)
}

// Mainland close data is published after trading; fund NAVs are published in the evening.
// Always use market time, even if the NAS or its container uses UTC.
func marketTime(now time.Time) time.Time {
	zone, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		zone = time.FixedZone("CST", 8*3600)
	}
	return now.In(zone)
}
func due(q Quote, now time.Time, force bool) bool {
	now = marketTime(now)
	at := q.AttemptedAt
	if at == 0 {
		at = q.FetchedAt
	}
	if at == 0 {
		return true
	}
	last := time.UnixMilli(at).In(now.Location())
	age := now.Sub(last)
	if q.Error != "" {
		if force {
			return age >= 30*time.Second
		}
		return age >= 5*time.Minute
	}
	if force && age >= 15*time.Minute {
		return true
	}
	hour, minute := 21, 0
	if !strings.HasPrefix(q.Symbol, "FUND:") {
		hour, minute = 15, 15
	}
	cutoff := time.Date(now.Year(), now.Month(), now.Day(), hour, minute, 0, 0, now.Location())
	if age >= time.Minute && ((!now.Before(cutoff) && last.Before(cutoff)) || last.Format("2006-01-02") != now.Format("2006-01-02")) {
		return true
	}
	// Publication can lag the first successful request. Recheck an older dated quote
	// within a bounded close/NAV publication window; holidays retain their trade date.
	endHour := 18
	if strings.HasPrefix(q.Symbol, "FUND:") {
		endHour = 24
	}
	return !now.Before(cutoff) && now.Hour() < endHour && q.Date < now.Format("2006-01-02") && age >= 15*time.Minute
}
func (s *Service) Run(ctx context.Context) {
	t := time.NewTicker(10 * time.Minute)
	defer t.Stop()
	s.refresh(ctx, false)
	for {
		select {
		case <-ctx.Done():
			return
		case force := <-s.pending:
			s.refresh(ctx, force)
		case <-t.C:
			s.refresh(ctx, false)
		}
	}
}
func (s *Service) refresh(ctx context.Context, force bool) { s.refreshSelected(ctx, force, nil) }
func (s *Service) refreshSelected(ctx context.Context, force bool, symbols []string) {
	if s.provider == nil {
		return
	}
	select {
	case s.gate <- struct{}{}:
	case <-ctx.Done():
		return
	}
	defer func() { <-s.gate }()
	selected := map[string]bool{}
	for _, k := range symbols {
		selected[k] = true
	}
	s.mu.Lock()
	keys := []string{}
	now := s.now()
	for k, q := range s.items {
		if (symbols == nil || selected[k]) && due(q, now, force) {
			keys = append(keys, k)
		}
	}
	s.mu.Unlock()
	sort.Strings(keys)
	for _, k := range keys {
		if ctx.Err() != nil {
			return
		}
		call, cancel := context.WithTimeout(ctx, 12*time.Second)
		q, err := s.provider.Fetch(call, k)
		cancel()
		if err == nil {
			if q.Symbol != k {
				err = errors.New("行情代码不匹配")
			} else {
				_, err = validate(q)
			}
		}
		s.mu.Lock()
		old := s.items[k]
		attempt := s.now().UnixMilli()
		if err == nil && old.Date != "" && q.Date < old.Date {
			err = errors.New("行情日期早于已保存报价，保留旧报价")
		}
		if err != nil {
			old.Error = "更新失败：" + err.Error()
			old.AttemptedAt = attempt
			s.items[k] = old
		} else {
			q.FetchedAt = attempt
			q.AttemptedAt = attempt
			q.Error = ""
			s.items[k] = q
		}
		if err := s.saveLocked(); err != nil {
			v := s.items[k]
			v.Error = "行情缓存保存失败，请检查 NAS 存储"
			s.items[k] = v
		}
		s.mu.Unlock()
	}
}
func readResponse(c *http.Client, req *http.Request) ([]byte, error) {
	res, e := c.Do(req)
	if e != nil {
		return nil, errors.New("行情服务连接失败")
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("行情服务返回 HTTP %d", res.StatusCode)
	}
	b, e := io.ReadAll(io.LimitReader(res.Body, 8*1024*1024+1))
	if e != nil {
		return nil, e
	}
	if len(b) > 8*1024*1024 {
		return nil, errors.New("行情响应过大")
	}
	return b, nil
}

var decimal = regexp.MustCompile(`^[0-9]{1,10}(\.[0-9]{1,8})?$`)

func validate(q Quote) (Quote, error) {
	value, ok := new(big.Rat).SetString(q.Price)
	if !decimal.MatchString(q.Price) || !ok || value.Sign() <= 0 {
		return q, errors.New("行情价格格式无效")
	}
	date, e := time.Parse("2006-01-02", q.Date)
	if e != nil || date.Format("2006-01-02") > marketTime(time.Now()).Format("2006-01-02") {
		return q, errors.New("行情日期无效")
	}
	return q, nil
}

type Eastmoney struct{ client *http.Client }

func (e *Eastmoney) Name() string { return "公开行情（个人使用）" }
func (e *Eastmoney) Fetch(ctx context.Context, symbol string) (Quote, error) {
	if !Valid(symbol) {
		return Quote{}, errors.New("行情代码格式无效")
	}
	if !strings.HasPrefix(symbol, "FUND:") {
		parts := strings.Split(symbol, ":")
		prefix := "0"
		if parts[0] == "SH" {
			prefix = "1"
		}
		url := "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=" + prefix + "." + parts[1] + "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56&klt=101&fqt=0&end=20500101&lmt=5"
		// Reserve time for the independent fallback when the primary endpoint disconnects or stalls.
		primary, cancel := context.WithTimeout(ctx, 3*time.Second)
		req, _ := http.NewRequestWithContext(primary, "GET", url, nil)
		req.Header.Set("User-Agent", "Mozilla/5.0")
		req.Header.Set("Referer", "https://quote.eastmoney.com/")
		b, err := readResponse(e.client, req)
		if err == nil {
			var quote Quote
			quote, err = ParseStock(symbol, b, "东方财富公开收盘价（个人使用）")
			if err == nil {
				cancel()
				return quote, nil
			}
		}
		cancel()
		key := strings.ToLower(parts[0]) + parts[1]
		req, _ = http.NewRequestWithContext(ctx, "GET", "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="+key+",day,,,5,", nil)
		req.Header.Set("User-Agent", "Mozilla/5.0")
		req.Header.Set("Referer", "https://gu.qq.com/")
		b, err = readResponse(e.client, req)
		if err != nil {
			return Quote{}, err
		}
		return ParseTencentStock(symbol, b, "腾讯证券公开收盘价（个人使用）")
	}

	req, _ := http.NewRequestWithContext(ctx, "GET", "https://fund.eastmoney.com/pingzhongdata/"+strings.TrimPrefix(symbol, "FUND:")+".js", nil)
	b, err := readResponse(e.client, req)
	if err != nil {
		return Quote{}, err
	}
	return ParseFund(symbol, b, "天天基金公开净值（个人使用）")
}

// Parse data only. Downloaded JavaScript is never evaluated.
func ParseFund(symbol string, b []byte, source string) (Quote, error) {
	re := regexp.MustCompile(`(?s)\bvar\s+Data_netWorthTrend\s*=\s*(\[.*?\])\s*;`)
	m := re.FindSubmatch(b)
	if m == nil {
		return Quote{}, errors.New("净值字段缺失")
	}
	var rows []struct {
		X int64       `json:"x"`
		Y json.Number `json:"y"`
	}
	d := json.NewDecoder(bytes.NewReader(m[1]))
	d.UseNumber()
	if err := d.Decode(&rows); err != nil {
		return Quote{}, errors.New("净值数据无法解析")
	}
	if len(rows) == 0 {
		return Quote{}, errors.New("暂无净值")
	}
	latest := rows[0]
	for _, r := range rows {
		if r.X > latest.X {
			latest = r
		}
	}
	return validate(Quote{Symbol: symbol, Price: latest.Y.String(), Date: marketTime(time.UnixMilli(latest.X)).Format("2006-01-02"), Source: source})
}

type Tushare struct {
	client *http.Client
	token  string
}

func (t *Tushare) Name() string { return "Tushare（NAS 自有授权）" }
func (t *Tushare) Fetch(ctx context.Context, symbol string) (Quote, error) {
	code := strings.Split(symbol, ":")[1]
	market := strings.Split(symbol, ":")[0]
	apiName := "daily"
	fields := "trade_date,close"
	ts := code + "." + market
	if market == "FUND" {
		apiName = "fund_nav"
		fields = "nav_date,unit_nav"
		ts = code + ".OF"
	}
	body, _ := json.Marshal(map[string]any{"api_name": apiName, "token": t.token, "params": map[string]string{"ts_code": ts, "start_date": time.Now().AddDate(0, 0, -14).Format("20060102"), "end_date": time.Now().Format("20060102")}, "fields": fields})
	req, _ := http.NewRequestWithContext(ctx, "POST", "https://api.tushare.pro", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	b, e := readResponse(t.client, req)
	if e != nil {
		return Quote{}, e
	}
	var response struct {
		Code int
		Data struct {
			Fields []string
			Items  [][]json.RawMessage
		}
	}
	if e = json.Unmarshal(b, &response); e != nil || response.Code != 0 {
		return Quote{}, errors.New("授权行情接口拒绝或返回格式异常，请检查 NAS 端权限")
	}
	dateField := strings.Split(fields, ",")[0]
	priceField := strings.Split(fields, ",")[1]
	di, pi := -1, -1
	for i, f := range response.Data.Fields {
		if f == dateField {
			di = i
		}
		if f == priceField {
			pi = i
		}
	}
	if di < 0 || pi < 0 {
		return Quote{}, errors.New("授权行情接口缺少字段")
	}
	var best Quote
	for _, row := range response.Data.Items {
		if di >= len(row) || pi >= len(row) {
			continue
		}
		var date string
		if json.Unmarshal(row[di], &date) != nil || len(date) != 8 {
			continue
		}
		if date > strings.ReplaceAll(best.Date, "-", "") {
			best = Quote{Symbol: symbol, Date: date[:4] + "-" + date[4:6] + "-" + date[6:], Price: strings.Trim(string(row[pi]), "\""), Source: t.Name()}
		}
	}
	if best.Price == "" {
		return best, errors.New("近两周没有有效价格，保留旧报价")
	}
	return validate(best)
}

func ParseStock(symbol string, b []byte, source string) (Quote, error) {
	var response struct {
		RC   int `json:"rc"`
		Data *struct {
			Klines []string `json:"klines"`
		} `json:"data"`
	}
	if json.Unmarshal(b, &response) != nil || response.RC != 0 || response.Data == nil {
		return Quote{}, errors.New("股票收盘价字段缺失")
	}
	var best Quote
	for _, line := range response.Data.Klines {
		p := strings.Split(line, ",")
		if len(p) >= 3 && closedSession(p[0], marketTime(time.Now())) && p[0] > best.Date {
			best = Quote{Symbol: symbol, Date: p[0], Price: p[2], Source: source}
		}
	}
	if best.Price == "" {
		return best, errors.New("暂无股票收盘价")
	}
	return validate(best)
}

// Intraday daily candles are unfinished. Keep the previous completed session until 15:15 CST.
func closedSession(date string, now time.Time) bool {
	d, err := time.Parse("2006-01-02", date)
	if err != nil {
		return false
	}
	today := marketTime(now).Format("2006-01-02")
	return d.Format("2006-01-02") < today || (date == today && marketTime(now).Hour()*60+marketTime(now).Minute() >= 15*60+15)
}
func ParseTencentStock(symbol string, b []byte, source string) (Quote, error) {
	if !Valid(symbol) || strings.HasPrefix(symbol, "FUND:") {
		return Quote{}, errors.New("股票代码格式无效")
	}
	key := strings.ToLower(strings.ReplaceAll(symbol, ":", ""))
	var response struct {
		Code int `json:"code"`
		Data map[string]struct {
			Day [][]string `json:"day"`
		} `json:"data"`
	}
	if json.Unmarshal(b, &response) != nil || response.Code != 0 {
		return Quote{}, errors.New("备用股票行情格式异常")
	}
	var best Quote
	// Only unadjusted day rows are accepted. qfqday/hfqday are not actual portfolio prices.
	for _, row := range response.Data[key].Day {
		if len(row) >= 3 && closedSession(row[0], time.Now()) && row[0] > best.Date {
			best = Quote{Symbol: symbol, Price: row[2], Date: row[0], Source: source}
		}
	}
	if best.Price == "" {
		return best, errors.New("暂无已收盘的不复权股票价格")
	}
	return validate(best)
}
