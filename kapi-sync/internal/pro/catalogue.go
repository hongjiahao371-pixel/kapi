package pro

import "encoding/json"

type Product struct {
	ID          string `json:"id"`
	Entitlement string `json:"entitlement"`
	Title       string `json:"title"`
	Currency    string `json:"currency"`
	AmountMinor int64  `json:"amount_minor"`
	Permanent   bool   `json:"permanent"`
	PriceLabel  string `json:"price_label"`
}
type Catalogue struct {
	Product   Product             `json:"product"`
	Groups    map[string][]string `json:"groups"`
	Platforms map[string][]string `json:"platforms"`
}

// Return independent values: HTTP clients can never mutate the shared policy.
func ProductCatalogue() Catalogue {
	var value Catalogue
	if err := json.Unmarshal([]byte(catalogueJSON), &value); err != nil {
		panic("invalid generated Pro catalogue")
	}
	return value
}
