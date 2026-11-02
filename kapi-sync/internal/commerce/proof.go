package commerce

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
)

// A per-request challenge stops a previously paid response from concealing a
// later refund. This proof never contains a wallet token or ledger information.
func (s *Service) VerifiedOrder(id, token, nonce string) (OrderView, error) {
	if len(nonce) != 32 {
		return OrderView{}, ErrInvalid
	}
	if _, err := hex.DecodeString(nonce); err != nil {
		return OrderView{}, ErrInvalid
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	o, ok := s.state.Orders[id]
	if !ok || !authorized(o, token) {
		return OrderView{}, ErrNotFound
	}
	if s.damaged || s.key == nil {
		return OrderView{}, ErrClosed
	}
	v := view(o)
	revision := o.Revision
	if revision < 1 {
		revision = 1
	}
	checkout := sha256.Sum256([]byte(v.Checkout.Kind + "\n" + v.Checkout.Value))
	body, err := json.Marshal(map[string]any{"id": o.ID, "state": o.State, "product_id": o.ProductID, "amount_minor": o.AmountMinor, "currency": o.Currency, "platform": o.Platform, "channel": o.Channel, "permanent": true, "revision": revision, "nonce": nonce, "checkout_sha256": hex.EncodeToString(checkout[:])})
	if err != nil {
		return OrderView{}, err
	}
	sum := sha256.Sum256(body)
	signature, err := ecdsa.SignASN1(rand.Reader, s.key, sum[:])
	if err != nil {
		return OrderView{}, err
	}
	v.EntitlementProof = "GZO1." + base64.RawURLEncoding.EncodeToString(body) + "." + base64.RawURLEncoding.EncodeToString(signature)
	return v, nil
}

type Refund struct {
	ID          string `json:"id"`
	AmountMinor int64  `json:"amount_minor"`
}

func (s *Service) refundLocked(o Order, r Receipt) error {
	if r.RefundID == "" || len(r.RefundID) > 128 || r.RefundMinor <= 0 || r.RefundMinor > o.AmountMinor || (o.TradeID != "" && r.TradeID != o.TradeID) {
		return ErrConflict
	}
	var total int64
	for _, refund := range o.Refunds {
		if refund.ID == r.RefundID {
			if refund.AmountMinor != r.RefundMinor {
				return ErrConflict
			}
			return nil
		}
		total += refund.AmountMinor
	}
	// A refund ID cannot be reused for a different order.
	for _, other := range s.state.Orders {
		if other.ID != o.ID && other.Channel == o.Channel {
			for _, refund := range other.Refunds {
				if refund.ID == r.RefundID {
					return ErrConflict
				}
			}
		}
	}
	if (o.State != "PAID" && o.State != "CREATED" && o.State != "EXPIRED" && o.State != "CLOSED") || total+r.RefundMinor > o.AmountMinor {
		return ErrConflict
	}
	o.TradeID = r.TradeID
	o.Refunds = append(append([]Refund{}, o.Refunds...), Refund{r.RefundID, r.RefundMinor})
	if total+r.RefundMinor == o.AmountMinor {
		o.State = "REFUNDED"
	}
	if o.Revision < 1 {
		o.Revision = 1
	}
	o.Revision++
	o.UpdatedAt = s.now().Unix()
	return s.save(o)
}
