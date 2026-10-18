package capture

import (
	"strings"
	"testing"
	"time"
)

func TestMoneyExactAndRejectsUnsafeInput(t *testing.T) {
	for _, v := range []struct {
		s string
		n int64
	}{{"9.9", 990}, {"0.01", 1}, {"999999999.99", 99999999999}, {"01.20", 120}} {
		n, e := Money(v.s)
		if e != nil || n != v.n {
			t.Fatal(v, n, e)
		}
	}
	for _, v := range []string{"-1", "0", "1e3", "NaN", "1.999", "1000000000.00", "9,900.00", "1x", "+1", ""} {
		if _, e := Money(v); e == nil {
			t.Fatal("accepted", v)
		}
	}
}
func TestReceiptParsingKeepsOneActualAmountAndTime(t *testing.T) {
	d, e := Parse("微信支付\n账单详情\n支付成功\n-18.90\n商户名称\n瑞幸咖啡\n商品说明\n美式咖啡\n支付时间\n2026-10-09 10:03:04\n交易单号\n4200002026100912345678", "00000000-0000-0000-0000-000000000001", "capture", time.Unix(1, 0))
	if e != nil || d.Amount != "18.90" || d.Merchant != "瑞幸咖啡" || !d.ReceiptTime || d.Blocked || !strings.HasPrefix(d.ExternalKey, "WECHAT_TXN:") {
		t.Fatalf("%+v %v", d, e)
	}
	if Classify(d) != "餐饮/咖啡茶饮" {
		t.Fatal(Classify(d))
	}
}
func TestAppleOCRInlineRowsPreserveMerchantTimeAndActualAmount(t *testing.T) {
	for _, gap := range []string{" ", "\t", "\u3000", "：", ": "} {
		text := "支付宝 · 支付成功\n¥ 9.90\n实付款" + gap + "9.90元\n商户名称" + gap + "瑞幸咖啡\n商品说明" + gap + "冰美式咖啡\n支付时间" + gap + "2026-10-09 12:20:30\n支付宝交易号\n208810092026101234567890"
		d, err := Parse(text, "00000000-0000-0000-0000-000000000001", "capture", time.Unix(1, 0))
		want := time.Date(2026, 10, 9, 12, 20, 30, 0, time.FixedZone("China", 8*3600)).UnixMilli()
		if err != nil || d.Amount != "9.90" || d.Merchant != "瑞幸咖啡" || d.Product != "冰美式咖啡" || !d.ReceiptTime || d.OccurredAt != want || Classify(d) != "餐饮/咖啡茶饮" {
			t.Fatalf("gap %q: %+v %v", gap, d, err)
		}
	}
}
func TestFieldPrefixDoesNotConsumeLongerLabel(t *testing.T) {
	got := field([]string{"商家名称设置", "商家名称：瑞幸咖啡"}, []string{"商家"}, textValue)
	if got != "" {
		t.Fatal(got)
	}
}
func TestPricesBalancesAndFailedPaymentsNeverBecomeConfirmedAmounts(t *testing.T) {
	for _, v := range []string{"商品价格\n19.99元\n立即购买", "支付成功\n余额\n1000.00\n支付金额\n12.34\n优惠金额\n1.00", "支付成功\n10.00\n20.00", "交易关闭\n12.00"} {
		d, _ := Parse(v, "00000000-0000-0000-0000-000000000001", "capture", time.Now())
		if strings.Contains(v, "支付金额") {
			if d.Amount != "12.34" {
				t.Fatal(d)
			}
		} else if d.Amount != "" {
			t.Fatal(d)
		}
	}
}
func TestRefundSelfTransfersAndForeignCurrencyAreBlocked(t *testing.T) {
	for _, v := range []string{"退款成功\n12.00", "提现到银行卡\n12.00", "信用卡还款\n200.00", "支付成功\nUSD\n19.00"} {
		d, _ := Parse(v, "00000000-0000-0000-0000-000000000001", "capture", time.Now())
		if !d.Blocked {
			t.Fatal(d)
		}
	}
}
func TestMerchantOrderDoesNotBecomeAuthoritativeIdentity(t *testing.T) {
	d, _ := Parse("支付成功\n19.00\n商户单号\n4200002026100912345678", "00000000-0000-0000-0000-000000000001", "capture", time.Now())
	if d.ExternalKey != "" {
		t.Fatal(d.ExternalKey)
	}
}
func TestSpecificProductWinsAndNavigationNeverClassifies(t *testing.T) {
	for _, v := range []struct{ m, p, raw, want string }{{"淘宝", "蓝牙耳机", "付款方式\n交通银行", "购物/数码"}, {"MiniMax", "", "为您推荐\n游戏充值", "其他/软件服务"}, {"未知商户", "", "付款方式\n交通银行", ""}, {"Keeper", "", "", ""}, {"美团", "鸡公煲套餐", "", "餐饮/正餐"}} {
		d := Draft{Merchant: v.m, Product: v.p, Text: v.raw, Direction: "EXPENSE"}
		if got := Classify(d); got != v.want {
			t.Fatalf("%+v got %s", v, got)
		}
	}
}
func TestTransferDoesNotGuessRedPacketOrFuel(t *testing.T) {
	d := Draft{Merchant: "加油无忧", Product: "红包", Text: "对方账户", Direction: "EXPENSE"}
	if Classify(d) != "" {
		t.Fatal(Classify(d))
	}
}
func TestFingerprintIgnoresClockButKeepsTransactionNumbers(t *testing.T) {
	a := Draft{Mode: "capture", Text: "10:22\n支付成功\n10.00\n交易单号\n4200002026100912345678"}
	b := a
	b.Text = strings.Replace(a.Text, "10:22", "10:23", 1)
	if Fingerprint(a) != Fingerprint(b) {
		t.Fatal("clock changed identity")
	}
	b.Text = strings.Replace(a.Text, "12345678", "87654321", 1)
	if Fingerprint(a) == Fingerprint(b) {
		t.Fatal("transaction identity erased")
	}
}
