package pro

import "testing"

func TestPricingPreviewPreservesLegacyAndDoesNotSellTermsYet(t *testing.T) {
	c := ProductCatalogue()
	if c.Pricing.Version != 2 || c.Pricing.CheckoutReady || c.Pricing.AutoRenew || len(c.Pricing.Offers) != 3 {
		t.Fatal("pricing must remain closed")
	}
	amounts := []int64{690, 6900, 9900}
	days := []int{31, 365, 0}
	for i, o := range c.Pricing.Offers {
		if o.AmountMinor != amounts[i] || o.DurationDays != days[i] || o.Permanent != (i == 2) || o.ID == ProductID || o.Currency != "CNY" {
			t.Fatal("price, term or legacy ID changed", i)
		}
	}
	if PolicyVersion != 1 || PriceMinor != 2990 || c.Product.ID != ProductID {
		t.Fatal("legacy verification was repriced")
	}
	c.Pricing.Offers[0].AmountMinor = 1
	if ProductCatalogue().Pricing.Offers[0].AmountMinor != 690 {
		t.Fatal("mutable shared pricing")
	}
}
