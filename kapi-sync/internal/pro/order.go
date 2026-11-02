package pro

import (
	"encoding/json"
	"errors"
	"regexp"
)

// Verification does not mutate data. Only Manager.Activate writes local rights.
func OrderActivation(response []byte, expectedID, publicKey string) (string, error) {
	invalid := errors.New("订单未通过核验，原有权益保持不变")
	if len(response) > 32768 || !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(expectedID) {
		return "", invalid
	}
	var value struct {
		ID       string `json:"id"`
		State    string `json:"state"`
		Product  string `json:"product_id"`
		Currency string `json:"currency"`
		Amount   int64  `json:"amount_minor"`
		Code     string `json:"activation_code"`
	}
	if json.Unmarshal(response, &value) != nil || value.ID != expectedID || value.State != "PAID" || value.Product != ProductID || value.Currency != "CNY" || value.Amount != PriceMinor {
		return "", invalid
	}
	info, e := Verify(value.Code, publicKey)
	if e != nil || info.ID != "order-"+expectedID {
		return "", invalid
	}
	return value.Code, nil
}
func (m *Manager) ActivatePaidOrder(response []byte, expectedID string) error {
	code, e := OrderActivation(response, expectedID, PublicKey)
	if e != nil {
		return e
	}
	return m.Activate(code)
}
