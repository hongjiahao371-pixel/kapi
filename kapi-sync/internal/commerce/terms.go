package commerce

import (
	"kapi/sync/internal/pro"
	"sort"
)

type accountGrant struct {
	Permanent       bool
	Until, Revision int64
}

// Derive from verified, durable orders. Never add days during a read, retry or
// restore. A full refund removes only its own grant; partial refunds retain it.
func (s *Service) grantLocked(owner string) accountGrant {
	var g accountGrant
	rows := []Order{}
	for _, o := range s.state.Orders {
		if o.OwnerHash != owner {
			continue
		}
		rev := o.Revision
		if rev < 1 {
			rev = 1
		}
		g.Revision += rev
		if o.State != "PAID" {
			continue
		}
		p, ok := pro.PurchaseOffer(o.ProductID)
		if !ok || p.AmountMinor != o.AmountMinor {
			continue
		}
		if p.Permanent {
			g.Permanent = true
			continue
		}
		if o.PaidAt > 0 {
			rows = append(rows, o)
		}
	}
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].PaidAt == rows[j].PaidAt {
			return rows[i].ID < rows[j].ID
		}
		return rows[i].PaidAt < rows[j].PaidAt
	})
	for _, o := range rows {
		p, _ := pro.PurchaseOffer(o.ProductID)
		if g.Until < o.PaidAt {
			g.Until = o.PaidAt
		}
		g.Until += int64(p.DurationDays) * 86400
	}
	if g.Permanent {
		g.Until = 0
	}
	return g
}

func productTitle(o Order) string {
	if p, ok := pro.PurchaseOffer(o.ProductID); ok {
		return "归账 " + p.Title
	}
	return pro.ProductCatalogue().Product.Title
}
