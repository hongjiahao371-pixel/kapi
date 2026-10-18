// Package capture parses user-invoked Shortcuts OCR. It never writes a ledger.
package capture

import (
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
)

//go:embed rules.json
var ruleData []byte

type Rule struct {
	Major        string   `json:"major"`
	Minor        *string  `json:"minor"`
	Keywords     []string `json:"keywords"`
	MerchantOnly bool     `json:"merchant_only"`
	RawOnly      bool     `json:"raw_only"`
}

var rules struct {
	Expense, Income []Rule
	SourceSHA256    string `json:"source_sha256"`
}

func init() {
	if err := json.Unmarshal(ruleData, &rules); err != nil {
		panic(err)
	}
}

// Money accepts a decimal string, not JSON floats or scientific notation.
func Money(s string) (int64, error) {
	s = strings.TrimSpace(s)
	if !regexp.MustCompile(`^[0-9]{1,9}(\.[0-9]{1,2})?$`).MatchString(s) {
		return 0, errors.New("金额需为正数，最多两位小数")
	}
	p := strings.Split(s, ".")
	major, _ := strconv.ParseInt(p[0], 10, 64)
	minor := int64(0)
	if len(p) == 2 {
		v := p[1]
		if len(v) == 1 {
			v += "0"
		}
		minor, _ = strconv.ParseInt(v, 10, 64)
	}
	n := major*100 + minor
	if n <= 0 {
		return 0, errors.New("金额必须大于 0")
	}
	return n, nil
}
func MoneyText(n int64) string {
	return strconv.FormatInt(n/100, 10) + "." + string([]byte{'0' + byte(n%100/10), '0' + byte(n%10)})
}

type Draft struct {
	RequestID   string   `json:"request_id"`
	Mode        string   `json:"mode"`
	Text        string   `json:"text"`
	Amount      string   `json:"amount"`
	Direction   string   `json:"direction"`
	Merchant    string   `json:"merchant"`
	Product     string   `json:"product"`
	OccurredAt  int64    `json:"occurred_at"`
	ReceiptTime bool     `json:"receipt_time"`
	ExternalKey string   `json:"external_key"`
	Warnings    []string `json:"warnings"`
	Blocked     bool     `json:"blocked"`
}

var amountRE = regexp.MustCompile(`^[+-]?\s*[¥￥]?\s*([0-9]{1,9}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*(?:元)?$`)
var dateRE = regexp.MustCompile(`20[0-9]{2}[-/年][0-9]{1,2}[-/月][0-9]{1,2}日?\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?`)
var idRE = regexp.MustCompile(`^(?:[A-Za-z0-9]{12,80}|[0-9]{6}-[0-9]{9,18})$`)
var statusClock = regexp.MustCompile(`^[0-9]{1,2}:[0-9]{2}(?:\s.*)?$`)

func lines(text string) []string {
	var out []string
	for _, v := range strings.Split(strings.ReplaceAll(text, "\r", "\n"), "\n") {
		v = strings.TrimSpace(v)
		if v != "" {
			out = append(out, v)
		}
	}
	return out
}

// OCR is normally in visual order; reverse adjacent values are accepted only by a validator.
func field(ls []string, labels []string, valid func(string) bool) string {
	for _, label := range labels {
		for i, l := range ls {
			if strings.HasPrefix(l, label) && len(l) > len(label) {
				tail := l[len(label):]
				// Apple OCR can keep a visual field/value row as one line,
				// separated by whitespace rather than a colon.
				first := []rune(tail)[0]
				if first == '：' || first == ':' || unicode.IsSpace(first) {
					v := strings.TrimSpace(strings.TrimLeftFunc(tail, func(r rune) bool {
						return r == '：' || r == ':' || unicode.IsSpace(r)
					}))
					if valid(v) {
						return v
					}
				}
			}
			if l == label {
				for _, j := range []int{i + 1, i - 1} {
					if j >= 0 && j < len(ls) && valid(ls[j]) {
						return ls[j]
					}
				}
			}
		}
	}
	return ""
}
func amount(s string) string {
	m := amountRE.FindStringSubmatch(s)
	if len(m) < 2 {
		return ""
	}
	v := strings.ReplaceAll(m[1], ",", "")
	n, e := Money(v)
	if e != nil {
		return ""
	}
	return MoneyText(n)
}
func textValue(s string) bool {
	if len([]rune(s)) > 160 || amount(s) != "" || dateRE.MatchString(s) {
		return false
	}
	for _, v := range []string{"支付时间", "付款方式", "支付方式", "交易单号", "交易号", "商品说明", "商品名称", "商户单号", "收款方", "商家名称", "商户名称", "账单分类", "备注", "订单号", "当前状态", "支付成功", "交易成功", "交易详情", "账单详情", "计入收支", "为您推荐"} {
		if s == v {
			return false
		}
	}
	return s != "" && !idRE.MatchString(s)
}
func Parse(text, requestID, mode string, capturedAt time.Time) (Draft, error) {
	if !regexp.MustCompile(`^[A-Za-z0-9-]{16,80}$`).MatchString(requestID) {
		return Draft{}, errors.New("记录标识无效，请重新运行快捷指令")
	}
	if mode != "capture" && mode != "manual" {
		return Draft{}, errors.New("不支持的记账方式")
	}
	if len(text) > 24000 {
		return Draft{}, errors.New("截图文字过长，请只截一笔账单详情")
	}
	d := Draft{RequestID: requestID, Mode: mode, Text: strings.TrimSpace(text), Direction: "EXPENSE", OccurredAt: capturedAt.UnixMilli(), Warnings: []string{}}
	if mode == "manual" {
		return d, nil
	}
	ls := lines(d.Text)
	for _, v := range []string{"支付失败", "交易关闭", "待付款", "待支付", "退款处理中", "退款成功", "退款到账", "退款方式", "提现到", "信用卡还款", "转入零钱通", "转出零钱通", "余额宝转入", "余额宝转出"} {
		if strings.Contains(text, v) {
			d.Blocked = true
			d.Warnings = append(d.Warnings, "这类页面首版不直接入账，请在安卓 App 处理退款关联或账户划转。")
			return d, nil
		}
	}
	if strings.Contains(text, "对方账户") || strings.Contains(text, "转给") {
		d.Warnings = append(d.Warnings, "转账不自动猜用途，请核对收支与分类。")
	}
	if strings.Contains(text, "美元") || strings.Contains(text, "USD") || strings.Contains(text, "€") || strings.Contains(text, "$ ") {
		d.Warnings = append(d.Warnings, "首版仅支持人民币，请不要直接录入外币金额。")
		d.Blocked = true
		return d, nil
	}
	success := strings.Contains(text, "支付成功") || strings.Contains(text, "交易成功") || strings.Contains(text, "付款成功") || strings.Contains(text, "收款成功") || strings.Contains(text, "交易完成")
	detail := strings.Contains(text, "账单详情") || strings.Contains(text, "交易详情")
	d.Amount = amount(field(ls, []string{"实付款", "实际付款", "实付金额", "实付", "支付金额", "付款金额", "交易金额", "支出金额", "收入金额", "合计"}, func(v string) bool { return amount(v) != "" }))
	if d.Amount == "" && (success || detail) {
		unique := map[string]bool{}
		for _, v := range ls {
			if a := amount(v); a != "" {
				unique[a] = true
			}
		}
		if len(unique) == 1 {
			for a := range unique {
				d.Amount = a
			}
		} else if len(unique) > 1 {
			d.Warnings = append(d.Warnings, "页面有多个金额，请填写实际支付金额。")
		}
	}
	if !success && !detail {
		d.Amount = ""
		d.Warnings = append(d.Warnings, "没有确认这是已支付账单，请手动核对后再保存。")
	}
	if strings.Contains(text, "收入金额") || strings.Contains(text, "收款成功") || strings.Contains(text, "收入\n") {
		d.Direction = "INCOME"
	}
	d.Merchant = field(ls, []string{"商户名称", "商家名称", "收款方", "交易对方", "商户全称", "商家"}, textValue)
	d.Product = field(ls, []string{"商品说明", "商品名称", "交易说明", "商品"}, textValue)
	if d.Merchant == "" {
		d.Warnings = append(d.Warnings, "未可靠识别商户，请填写或留空。")
	}
	when := field(ls, []string{"支付时间", "付款时间", "交易时间", "收款时间"}, dateRE.MatchString)
	if when != "" {
		v := dateRE.FindString(when)
		v = strings.NewReplacer("年", "-", "月", "-", "日", "", "/", "-").Replace(v)
		for _, layout := range []string{"2006-1-2 15:04:05", "2006-1-2 15:04"} {
			if t, e := time.ParseInLocation(layout, v, time.FixedZone("China", 8*3600)); e == nil {
				d.OccurredAt = t.UnixMilli()
				d.ReceiptTime = true
				break
			}
		}
	}
	if !d.ReceiptTime {
		d.Warnings = append(d.Warnings, "未识别支付时间，暂用截图时间；历史账单请修正日期。")
	}
	for _, label := range []string{"支付宝交易号", "微信支付交易单号", "交易单号", "交易号", "多多支付交易单号"} {
		if v := field(ls, []string{label}, idRE.MatchString); v != "" {
			kind := "WECHAT_TXN"
			if label == "交易号" || label == "支付宝交易号" {
				kind = "ALIPAY_TXN"
			}
			if label == "多多支付交易单号" {
				kind = "PDD_TXN"
			}
			d.ExternalKey = kind + ":" + v
			break
		}
	}
	if d.ExternalKey == "" {
		d.Warnings = append(d.Warnings, "没有可靠交易号；同一保存任务可安全重试，新截图仍需核对是否重复。")
	}
	return d, nil
}
func Fingerprint(d Draft) string {
	if d.Mode != "capture" || d.Text == "" {
		return ""
	}
	var ls []string
	for _, v := range lines(d.Text) {
		if !statusClock.MatchString(v) {
			ls = append(ls, strings.Join(strings.Fields(v), ""))
		}
	}
	sum := sha256.Sum256([]byte(strings.Join(ls, "\n")))
	return hex.EncodeToString(sum[:])
}
func keyword(text, key string) bool {
	text = strings.ToLower(text)
	key = strings.ToLower(key)
	start := 0
	for start < len(text) {
		i := strings.Index(text[start:], key)
		if i < 0 {
			return false
		}
		i += start
		ascii := true
		hasLetter := false
		for _, r := range key {
			if r > 127 {
				ascii = false
			}
			if unicode.IsLetter(r) {
				hasLetter = true
			}
		}
		if !ascii || !hasLetter {
			return true
		}
		word := func(b byte) bool { return b >= 'a' && b <= 'z' || b >= '0' && b <= '9' }
		if (i == 0 || !word(text[i-1])) && (i+len(key) == len(text) || !word(text[i+len(key)])) {
			return true
		}
		start = i + len(key)
	}
	return false
}

// Classify reuses the Android keyword table, with structured product evidence before merchant.
func Classify(d Draft) string {
	if strings.Contains(d.Text, "对方账户") || strings.Contains(d.Text, "转给") {
		return ""
	}
	rs := rules.Expense
	if d.Direction == "INCOME" {
		rs = rules.Income
	}
	path := func(r Rule) string {
		if r.Minor != nil {
			return r.Major + "/" + *r.Minor
		}
		return r.Major
	}
	for _, stage := range []int{0, 1, 2, 3} {
		for _, r := range rs {
			text := d.Product
			switch stage {
			case 0:
				if r.MerchantOnly || r.RawOnly {
					continue
				}
			case 1:
				if !r.MerchantOnly {
					continue
				}
				text = d.Merchant
			case 2:
				if r.MerchantOnly || r.RawOnly {
					continue
				}
				text = d.Merchant
			case 3:
				if !r.RawOnly || r.MerchantOnly {
					continue
				}
				text = d.Merchant + "\n" + d.Product
			}
			for _, k := range r.Keywords {
				if keyword(text, k) {
					return path(r)
				}
			}
		}
	}
	return ""
}
