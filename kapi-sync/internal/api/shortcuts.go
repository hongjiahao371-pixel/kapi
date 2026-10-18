package api

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"kapi/sync/internal/capture"
	"kapi/sync/internal/store"
)

type shortcutEnvelope struct {
	DeviceID string        `json:"device_id"`
	Expires  int64         `json:"expires"`
	Draft    capture.Draft `json:"draft"`
}

func signShortcut(d *store.Device, p shortcutEnvelope) string {
	b, _ := json.Marshal(p)
	part := base64.RawURLEncoding.EncodeToString(b)
	mac := hmac.New(sha256.New, []byte(d.TokenHash))
	mac.Write([]byte(part))
	return part + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
func readShortcut(d *store.Device, token string) (capture.Draft, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return capture.Draft{}, errors.New("预览凭据无效，请重新预览")
	}
	sig, e := base64.RawURLEncoding.DecodeString(parts[1])
	if e != nil {
		return capture.Draft{}, errors.New("预览凭据无效")
	}
	mac := hmac.New(sha256.New, []byte(d.TokenHash))
	mac.Write([]byte(parts[0]))
	if !hmac.Equal(sig, mac.Sum(nil)) {
		return capture.Draft{}, errors.New("预览凭据与设备不匹配")
	}
	b, e := base64.RawURLEncoding.DecodeString(parts[0])
	if e != nil {
		return capture.Draft{}, e
	}
	var p shortcutEnvelope
	if e = json.Unmarshal(b, &p); e != nil {
		return capture.Draft{}, e
	}
	if p.DeviceID != d.ID || p.Expires < time.Now().Unix() {
		return capture.Draft{}, errors.New("预览已过期，请重新预览；未提交的本地任务仍可继续")
	}
	return p.Draft, nil
}
func decodeShortcut(w http.ResponseWriter, r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 96<<10)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if e := dec.Decode(v); e != nil {
		return e
	}
	var extra any
	if e := dec.Decode(&extra); e != io.EOF {
		return errors.New("只允许一份 JSON 请求")
	}
	return nil
}
func (s *Server) handleShortcutCatalog(w http.ResponseWriter, r *http.Request, _ *store.Device) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, "GET")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, 200, s.st.ShortcutCatalog())
}
func labels(list []store.ShortcutOption) []string {
	out := []string{}
	for _, o := range list {
		out = append(out, o.Label)
	}
	return out
}
func optionLabel(list []store.ShortcutOption, id string) string {
	for _, o := range list {
		if o.ID == id {
			return o.Label
		}
	}
	return ""
}
func optionID(list []store.ShortcutOption, label string) string {
	for _, o := range list {
		if o.Label == label || o.ID == label {
			return o.ID
		}
	}
	return ""
}

func shortcutAccountSuggestion(c []store.ShortcutOption, d capture.Draft) string {
	name := ""
	switch {
	case strings.HasPrefix(d.ExternalKey, "ALIPAY_TXN:"):
		name = "支付宝"
	case strings.HasPrefix(d.ExternalKey, "WECHAT_TXN:"):
		name = "微信"
	case d.Mode == "capture":
		alipay, wechat := strings.Contains(d.Text, "支付宝"), strings.Contains(d.Text, "微信")
		if alipay && !wechat {
			name = "支付宝"
		}
		if wechat && !alipay {
			name = "微信"
		}
	}
	for _, option := range c {
		if name != "" && option.Name == name {
			return option.Label
		}
	}
	return "请手动选择"
}

// Shortcuts text dictionary values use "yes"; native callers may send JSON true.
func shortcutConfirmed(raw json.RawMessage) bool {
	return bytes.Equal(raw, []byte("true")) || bytes.Equal(raw, []byte(`"yes"`))
}
func (s *Server) handleShortcutPreview(w http.ResponseWriter, r *http.Request, device *store.Device) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, "POST")
		return
	}
	var body struct {
		RequestID      string `json:"request_id"`
		Mode           string `json:"mode"`
		Text           string `json:"text"`
		TextBase64     string `json:"text_base64"`
		CapturedAt     string `json:"captured_at"`
		Direction      string `json:"direction"`
		ManualAmount   string `json:"manual_amount"`
		ManualMerchant string `json:"manual_merchant"`
		ManualNote     string `json:"manual_note"`
	}
	if e := decodeShortcut(w, r, &body); e != nil {
		writeErr(w, 400, "bad_body", e.Error())
		return
	}
	if body.TextBase64 != "" {
		v, e := base64.StdEncoding.DecodeString(strings.Join(strings.Fields(body.TextBase64), ""))
		if e != nil || !utf8.Valid(v) {
			writeErr(w, 400, "bad_text", "截图文字编码无效")
			return
		}
		body.Text = string(v)
	}
	when := time.Now()
	if body.CapturedAt != "" {
		var e error
		when, e = time.Parse(time.RFC3339, body.CapturedAt)
		if e != nil {
			writeErr(w, 400, "bad_date", "截图日期格式无效")
			return
		}
	}
	d, e := capture.Parse(body.Text, body.RequestID, body.Mode, when)
	if e != nil {
		writeErr(w, 400, "bad_capture", e.Error())
		return
	}
	if body.Direction != "" {
		if body.Direction == "支出" {
			body.Direction = "EXPENSE"
		}
		if body.Direction == "收入" {
			body.Direction = "INCOME"
		}
		if body.Direction != "INCOME" && body.Direction != "EXPENSE" {
			writeErr(w, 400, "bad_direction", "收支方向无效")
			return
		}
		d.Direction = body.Direction
	}
	if d.Mode == "manual" {
		if body.ManualAmount != "" {
			n, e := capture.Money(body.ManualAmount)
			if e != nil {
				writeErr(w, 400, "bad_amount", e.Error())
				return
			}
			d.Amount = capture.MoneyText(n)
		}
		if len([]rune(body.ManualMerchant)) > 160 || len([]rune(body.ManualNote)) > 2000 {
			writeErr(w, 400, "too_long", "商户或备注太长")
			return
		}
		d.Merchant = strings.TrimSpace(body.ManualMerchant)
		d.Product = strings.TrimSpace(body.ManualNote)
	}
	c := s.st.ShortcutCatalog()
	ledger := "L1"
	if optionLabel(c.Ledgers, ledger) == "" && len(c.Ledgers) > 0 {
		ledger = c.Ledgers[0].ID
	}
	account := shortcutAccountSuggestion(c.Accounts, d)
	category := s.st.ShortcutCategory(d, ledger)
	categoryLabels := []string{"未分类"}
	for _, o := range c.Categories {
		if o.Kind == d.Direction {
			categoryLabels = append(categoryLabels, o.Label)
		}
	}
	defaultCategory := optionLabel(c.Categories, category)
	if defaultCategory == "" {
		defaultCategory = "未分类"
	}
	if len(c.Accounts) == 0 || len(c.Ledgers) == 0 {
		d.Blocked = true
		d.Warnings = append(d.Warnings, "NAS 上还没有账户与账本，请先在安卓 App 同步一次。")
	}
	note := d.Product
	if d.ExternalKey != "" {
		label := "交易单号"
		if strings.HasPrefix(d.ExternalKey, "ALIPAY_TXN:") {
			label = "支付宝交易号"
		}
		note = strings.TrimSpace(note + "\n" + label + "：" + strings.SplitN(d.ExternalKey, ":", 2)[1])
	}
	w.Header().Set("Cache-Control", "no-store")
	directionName := "支出"
	if d.Direction == "INCOME" {
		directionName = "收入"
	}
	canSave := "yes"
	if d.Blocked {
		canSave = "no"
	}
	writeJSON(w, 200, map[string]any{"draft_token": signShortcut(device, shortcutEnvelope{device.ID, time.Now().Add(24 * time.Hour).Unix(), d}), "request_id": d.RequestID, "amount": d.Amount, "merchant": d.Merchant, "note": note, "direction": d.Direction, "direction_name": directionName, "occurred_at": d.OccurredAt, "date_text": time.UnixMilli(d.OccurredAt).In(time.FixedZone("China", 8*3600)).Format("2006-01-02 15:04:05"), "warnings": d.Warnings, "warning_text": strings.Join(d.Warnings, "\n"), "blocked": d.Blocked, "can_save": canSave, "accounts": labels(c.Accounts), "ledgers": labels(c.Ledgers), "categories": categoryLabels, "category": defaultCategory, "account": account, "ledger": optionLabel(c.Ledgers, ledger), "message": "请核对金额、收支、日期、账户与分类。预览不会写入账本。"})
}
func (s *Server) handleShortcutCommit(w http.ResponseWriter, r *http.Request, device *store.Device) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, "POST")
		return
	}
	var body struct {
		DraftToken             string          `json:"draft_token"`
		Amount                 string          `json:"amount"`
		Direction              string          `json:"direction"`
		Merchant               string          `json:"merchant"`
		Note                   string          `json:"note"`
		Category               string          `json:"category"`
		Account                string          `json:"account"`
		Ledger                 string          `json:"ledger"`
		Date                   string          `json:"date"`
		Confirmed              json.RawMessage `json:"confirmed"`
		AllowPossibleDuplicate json.RawMessage `json:"allow_possible_duplicate"`
	}
	if e := decodeShortcut(w, r, &body); e != nil {
		writeErr(w, 400, "bad_body", e.Error())
		return
	}
	if !shortcutConfirmed(body.Confirmed) {
		writeErr(w, 400, "confirmation_required", "需要确认后才会保存")
		return
	}
	d, e := readShortcut(device, body.DraftToken)
	if e != nil {
		writeErr(w, 400, "invalid_draft", e.Error())
		return
	}
	if d.Blocked {
		writeErr(w, 400, "unsupported_capture", "这类截图不能直接保存，请按预览提示处理")
		return
	}
	n, e := capture.Money(body.Amount)
	if e != nil {
		writeErr(w, 400, "bad_amount", e.Error())
		return
	}
	if len([]rune(body.Merchant)) > 160 || len([]rune(body.Note)) > 2000 {
		writeErr(w, 400, "too_long", "商户或备注太长")
		return
	}
	when := d.OccurredAt
	if body.Date != "" {
		t, e := time.ParseInLocation("2006-01-02 15:04:05", body.Date, time.FixedZone("China", 8*3600))
		if e != nil || t.Year() < 2000 || t.Year() > 2100 {
			writeErr(w, 400, "bad_date", "日期需为 YYYY-MM-DD HH:mm:ss")
			return
		}
		when = t.UnixMilli()
	}
	c := s.st.ShortcutCatalog()
	category := ""
	if body.Category != "" && body.Category != "未分类" {
		category = optionID(c.Categories, body.Category)
		if category == "" {
			writeErr(w, 400, "bad_category", "分类已变化，请重新预览")
			return
		}
	}
	res, e := s.st.SaveShortcut(device.ID, store.ShortcutTxn{RequestID: d.RequestID, AmountMinor: n, Direction: body.Direction, Merchant: strings.TrimSpace(body.Merchant), Note: strings.TrimSpace(body.Note), CategoryID: category, AccountID: optionID(c.Accounts, body.Account), LedgerID: optionID(c.Ledgers, body.Ledger), OccurredAt: when, ExternalKey: d.ExternalKey, Fingerprint: capture.Fingerprint(d), AllowPossibleDuplicate: shortcutConfirmed(body.AllowPossibleDuplicate)})
	if e != nil {
		writeErr(w, 400, "save_failed", e.Error())
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, 200, res)
}
