package commerce

import (
	"context"
	"encoding/json"
	"fmt"
	"kapi/sync/internal/pro"
	"net/url"
	"strings"
	"testing"
)

func TestAfdianTermPurchaseBindsEachPriceToItsOwnPlanAndOneSKU(t *testing.T) {
	cfg := AfdianConfig{UserID: strings.Repeat("a", 32), Token: strings.Repeat("t", 40), ProductType: 1, Offers: map[string]AfdianOffer{}}
	offers := pro.ProductCatalogue().Pricing.Offers
	for i, o := range offers {
		cfg.Offers[o.ID] = AfdianOffer{PlanID: strings.Repeat(fmt.Sprint(i+1), 32), SKUID: strings.Repeat(fmt.Sprint(i+4), 32)}
	}
	p, e := NewAfdian(cfg)
	if e != nil {
		t.Fatal(e)
	}
	for _, offer := range offers {
		o := Order{ID: strings.Repeat("b", 32), ProductID: offer.ID, Platform: "android", Currency: "CNY", AmountMinor: offer.AmountMinor}
		checkout, e := p.Prepare(context.Background(), o)
		if e != nil {
			t.Fatal(e)
		}
		u, _ := url.Parse(checkout.Value)
		binding := cfg.Offers[offer.ID]
		if u.Query().Get("plan_id") != binding.PlanID || u.Query().Get("custom_order_id") != o.ID || u.Query().Get("product_type") != "1" {
			t.Fatal("wrong purchase plan")
		}
		var sku []struct {
			ID    string `json:"sku_id"`
			Count int    `json:"count"`
		}
		if json.Unmarshal([]byte(u.Query().Get("sku")), &sku) != nil || len(sku) != 1 || sku[0].ID != binding.SKUID || sku[0].Count != 1 {
			t.Fatal("sale checkout did not select exactly one bound SKU")
		}
		row := paidAfdian(p, o.ID)
		row.PlanID = binding.PlanID
		row.SKU[0].ID = binding.SKUID
		row.Total = fmt.Sprintf("%d.%02d", offer.AmountMinor/100, offer.AmountMinor%100)
		if _, e = p.verified(o, row); e != nil {
			t.Fatal("correct bound order rejected", e)
		}
		row.SKU[0].Count = 2
		if _, e = p.verified(o, row); e == nil {
			t.Fatal("double quantity granted one price")
		}
		row.SKU[0].Count = 1
		row.Total = "0.01"
		if _, e = p.verified(o, row); e == nil {
			t.Fatal("wrong price granted")
		}
	}
	cfg.Offers[offers[1].ID] = cfg.Offers[offers[0].ID]
	if _, e = NewAfdian(cfg); e == nil {
		t.Fatal("ambiguous shared checkout plans accepted")
	}
}
