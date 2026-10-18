package store

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math"
	"sort"
	"strings"

	"kapi/sync/internal/capture"
	"kapi/sync/internal/merge"
)

type ShortcutOption struct {
	ID    string `json:"id"`
	Path  string `json:"path,omitempty"`
	Name  string `json:"name"`
	Label string `json:"label"`
	Kind  string `json:"kind"`
}
type ShortcutCatalog struct {
	Accounts   []ShortcutOption `json:"accounts"`
	Categories []ShortcutOption `json:"categories"`
	Ledgers    []ShortcutOption `json:"ledgers"`
}

func payload(r *merge.Record) map[string]json.RawMessage {
	out := map[string]json.RawMessage{}
	_ = json.Unmarshal([]byte(jsonStr(r.Values["payload"])), &out)
	return out
}
func (s *Store) shortcutCatalogLocked() ShortcutCatalog {
	c := ShortcutCatalog{Accounts: []ShortcutOption{}, Categories: []ShortcutOption{}, Ledgers: []ShortcutOption{}}
	hasLedger := false
	for _, r := range s.rows {
		if r.Entity == "ledger" {
			hasLedger = true
		}
		if r.Deleted {
			continue
		}
		o := ShortcutOption{ID: r.EntityID, Name: jsonStr(r.Values["name"]), Kind: jsonStr(r.Values["kind"])}
		switch r.Entity {
		case "ledger":
			p := payload(r)
			o.Name = jsonStr(p["name"])
			if o.Name == "" {
				o.Name = jsonStr(r.Values["name"])
			}
			o.Label = o.Name + " · " + o.ID
			c.Ledgers = append(c.Ledgers, o)
		case EntityAccount:
			if v := jsonStr(r.Values["currency"]); v != "" && v != "CNY" {
				continue
			}
			o.Label = o.Name + " · " + o.ID
			c.Accounts = append(c.Accounts, o)
		case EntityCategory:
			if parent := jsonStr(r.Values["parent_id"]); parent != "" {
				p := s.rows[key(EntityCategory, parent)]
				if p == nil || p.Deleted {
					continue
				}
				prefix := jsonStr(p.Values["name"]) + "/"
				if !strings.HasPrefix(o.Name, prefix) {
					o.Name = prefix + o.Name
				}
			}
			o.Label = o.Name + " · " + o.ID
			c.Categories = append(c.Categories, o)
		}
	}
	if !hasLedger && len(c.Accounts) > 0 {
		c.Ledgers = append(c.Ledgers, ShortcutOption{ID: "L1", Name: "日常", Label: "日常 · L1"})
	}
	for _, list := range [][]ShortcutOption{c.Accounts, c.Categories, c.Ledgers} {
		sort.Slice(list, func(i, j int) bool {
			if list[i].Name == list[j].Name {
				return list[i].ID < list[j].ID
			}
			return list[i].Name < list[j].Name
		})
	}
	return c
}
func (s *Store) ShortcutCatalog() ShortcutCatalog {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.shortcutCatalogLocked()
}

// Suggestion uses synced user rules and explicit manual merchant corrections before built-ins.
func (s *Store) ShortcutCategory(d capture.Draft, ledger string) string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	c := s.shortcutCatalogLocked()
	valid := func(id string) bool {
		for _, o := range c.Categories {
			if o.ID == id && o.Kind == d.Direction {
				return true
			}
		}
		return false
	}
	type rule struct {
		ID, Kind, Pattern, Category, Ledger string
		Created                             int64
	}
	var rr []rule
	for _, r := range s.rows {
		if r.Entity != "category_rule" || r.Deleted {
			continue
		}
		p := payload(r)
		rr = append(rr, rule{r.EntityID, jsonStr(p["kind"]), jsonStr(p["pattern"]), jsonStr(p["categoryId"]), jsonStr(p["ledgerId"]), jsonInt(p["createdAt"])})
	}
	sort.Slice(rr, func(i, j int) bool {
		if rr[i].Kind != rr[j].Kind {
			return rr[i].Kind == "MERCHANT"
		}
		if len(rr[i].Pattern) != len(rr[j].Pattern) {
			return len(rr[i].Pattern) > len(rr[j].Pattern)
		}
		if rr[i].Created != rr[j].Created {
			return rr[i].Created > rr[j].Created
		}
		return rr[i].ID < rr[j].ID
	})
	for _, r := range rr {
		if r.Pattern == "" || r.Ledger != ledger || !valid(r.Category) {
			continue
		}
		text := d.Product
		if r.Kind == "MERCHANT" {
			text = d.Merchant
		} else if r.Kind != "KEYWORD" {
			continue
		}
		if strings.Contains(strings.ToLower(text), strings.ToLower(r.Pattern)) {
			return r.Category
		}
	}
	if d.Merchant != "" {
		ids := map[string]bool{}
		for _, r := range s.rows {
			if r.Entity != TxnEntity || r.Deleted {
				continue
			}
			v := r.Values
			if jsonStr(v["source"]) == "MANUAL" && jsonStr(v["ledger_id"]) == ledger && jsonStr(v["direction"]) == d.Direction && strings.EqualFold(jsonStr(v["merchant"]), d.Merchant) && valid(jsonStr(v["category_id"])) {
				ids[jsonStr(v["category_id"])] = true
			}
		}
		if len(ids) == 1 {
			for id := range ids {
				return id
			}
		}
	}
	path := capture.Classify(d)
	for _, o := range c.Categories {
		if o.Name == path && o.Kind == d.Direction {
			return o.ID
		}
	}
	return ""
}

type ShortcutTxn struct {
	RequestID                                                  string
	AmountMinor                                                int64
	Direction, Merchant, Note, CategoryID, AccountID, LedgerID string
	OccurredAt                                                 int64
	ExternalKey, Fingerprint                                   string
	AllowPossibleDuplicate                                     bool
}
type ShortcutSaved struct {
	Status  string `json:"status"`
	ID      string `json:"id"`
	Cursor  int64  `json:"cursor"`
	Message string `json:"message"`
}

// Atomic identity check + Lamport allocation + durable append. Retry never edits or resurrects.
func (s *Store) SaveShortcut(deviceID string, t ShortcutTxn) (*ShortcutSaved, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.devices[deviceID] == nil {
		return nil, ErrUnauthorized
	}
	// UUID is global to a queued task, so re-pairing a phone cannot duplicate a retry.
	sum := sha256.Sum256([]byte("shortcuts:" + t.RequestID))
	id := "shortcut-" + hex.EncodeToString(sum[:])
	if r := s.rows[key(TxnEntity, id)]; r != nil {
		status := "already_saved"
		msg := "这笔已保存，无需再次提交。"
		if r.Deleted {
			status = "already_deleted"
			msg = "这笔已删除，重试不会恢复它。"
		}
		return &ShortcutSaved{status, id, s.seq, msg}, nil
	}
	if t.AmountMinor <= 0 || t.AmountMinor > 99999999999 || t.OccurredAt <= 0 || t.Direction != DirExpense && t.Direction != DirIncome {
		return nil, errors.New("金额、日期或收支方向无效")
	}
	c := s.shortcutCatalogLocked()
	found := func(list []ShortcutOption, id string, kind string) bool {
		for _, o := range list {
			if o.ID == id && (kind == "" || o.Kind == kind) {
				return true
			}
		}
		return false
	}
	if !found(c.Ledgers, t.LedgerID, "") || !found(c.Accounts, t.AccountID, "") || t.CategoryID != "" && !found(c.Categories, t.CategoryID, t.Direction) {
		return nil, errors.New("账本、账户或分类已变化，请重新预览选择")
	}
	if t.ExternalKey != "" {
		value := strings.SplitN(t.ExternalKey, ":", 2)
		for _, r := range s.rows {
			if r.Entity != TxnEntity {
				continue
			}
			v := r.Values
			if jsonStr(v["ledger_id"]) != t.LedgerID {
				continue
			}
			match := jsonStr(v["shortcut_external_key"]) == t.ExternalKey
			if !match && len(value) == 2 && jsonInt(v["amount_minor"]) == t.AmountMinor && jsonStr(v["direction"]) == t.Direction {
				note := jsonStr(v["note"])
				match = strings.Contains(note, value[1]) && (strings.Contains(note, "交易号") || strings.Contains(note, "交易单号") || strings.Contains(note, "支付单号"))
			}
			if match {
				status := "already_saved"
				if r.Deleted {
					status = "already_deleted"
				}
				return &ShortcutSaved{status, r.EntityID, s.seq, "已找到相同交易号，未重复入账。"}, nil
			}
		}
	}
	if !t.AllowPossibleDuplicate {
		for _, r := range s.rows {
			if r.Entity != TxnEntity || r.Deleted {
				continue
			}
			v := r.Values
			if jsonStr(v["ledger_id"]) != t.LedgerID || jsonInt(v["amount_minor"]) != t.AmountMinor || jsonStr(v["direction"]) != t.Direction {
				continue
			}
			delta := jsonInt(v["occurred_at"]) - t.OccurredAt
			if delta < 0 {
				delta = -delta
			}
			if t.Fingerprint != "" && jsonStr(v["shortcut_fingerprint"]) == t.Fingerprint || delta <= 60000 && (t.Merchant != "" && strings.EqualFold(jsonStr(v["merchant"]), t.Merchant) || delta <= 3000 && jsonStr(v["account_id"]) == t.AccountID) {
				return &ShortcutSaved{"needs_review", r.EntityID, s.seq, "有金额、时间相近的账单。请先查看是否已记过；确实是另一笔时再选择“另记一笔”。"}, nil
			}
		}
	}
	max := int64(0)
	for _, r := range s.rows {
		if r.Lamport > max {
			max = r.Lamport
		}
	}
	if max == math.MaxInt64 {
		return nil, errors.New("版本计数已达上限")
	}
	values := map[string]json.RawMessage{"ledger_id": merge.V(t.LedgerID), "amount_minor": merge.V(t.AmountMinor), "direction": merge.V(t.Direction), "account_id": merge.V(t.AccountID), "to_account_id": merge.V(nil), "refund_of_id": merge.V(nil), "tags_json": merge.V("[]"), "category_id": merge.V(nil), "occurred_at": merge.V(t.OccurredAt), "merchant": merge.V(t.Merchant), "note": merge.V(t.Note), "source": merge.V("MANUAL"), "confidence": merge.V("1.0"), "shortcut_external_key": merge.V(t.ExternalKey), "shortcut_fingerprint": merge.V(t.Fingerprint)}
	if t.CategoryID != "" {
		values["category_id"] = merge.V(t.CategoryID)
	}
	ch := merge.Change{ChangeID: deviceID + ":shortcut/" + t.RequestID, Entity: TxnEntity, EntityID: id, Op: merge.OpUpsert, Data: values, Lamport: max + 1, Origin: deviceID}
	res, err := s.pushLocked(deviceID, []merge.Change{ch})
	if err != nil {
		return nil, err
	}
	if len(res.Rejected) > 0 {
		return nil, errors.New("保存被拒绝，请重新预览")
	}
	return &ShortcutSaved{"saved", id, s.seq, "已记入归账，安卓同步后可见。"}, nil
}
