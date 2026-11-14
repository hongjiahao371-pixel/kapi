package pro

// PurchaseOffer also recognizes the legacy price for verification, never for
// new public sales. Product IDs are immutable across price/term changes.
func PurchaseOffer(id string) (Offer, bool) {
	if id == ProductID {
		return Offer{ID: ProductID, Title: "旧版永久 Pro", Currency: "CNY", AmountMinor: PriceMinor, Permanent: true, PriceLabel: PriceLabel}, true
	}
	for _, o := range ProductCatalogue().Pricing.Offers {
		if o.ID == id {
			return o, true
		}
	}
	return Offer{}, false
}
