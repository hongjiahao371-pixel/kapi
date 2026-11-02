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
	Pricing   Pricing             `json:"pricing"`
	Groups    map[string][]string `json:"groups"`
	Platforms map[string][]string `json:"platforms"`
}

type Offer struct {
	ID           string `json:"id"`
	Title        string `json:"title"`
	Currency     string `json:"currency"`
	AmountMinor  int64  `json:"amount_minor"`
	Permanent    bool   `json:"permanent"`
	DurationDays int    `json:"duration_days"`
	PriceLabel   string `json:"price_label"`
}
type Pricing struct {
	Version       int     `json:"version"`
	CheckoutReady bool    `json:"checkout_ready"`
	Summary       string  `json:"summary"`
	AutoRenew     bool    `json:"auto_renew"`
	Offers        []Offer `json:"offers"`
}

// Return independent values: HTTP clients can never mutate the shared policy.
func ProductCatalogue() Catalogue {
	var value Catalogue
	if err := json.Unmarshal([]byte(catalogueJSON), &value); err != nil {
		panic("invalid generated Pro catalogue")
	}
	return value
}
