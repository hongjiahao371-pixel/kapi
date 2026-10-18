package store

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"kapi/sync/internal/billimport"
	"kapi/sync/internal/merge"
	"math"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

var ErrImportChanged = errors.New("同步数据刚刚变化，请重新核对预览后再确认")

func nullable(v json.RawMessage) *string {
	s := jsonStr(v)
	if s == "" {
		return nil
	}
	return &s
}
func ledgerOf(r *merge.Record) string {
	s := jsonStr(r.Values["ledger_id"])
	if s == "" {
		return "L1"
	}
	return s
}
func (s *Store) billCatalogLocked(ledger string) ShortcutCatalog {
	c := s.shortcutCatalogLocked()
	accounts := []ShortcutOption{}
	cats := []ShortcutOption{}
	for _, a := range c.Accounts {
		r := s.rows[key(EntityAccount, a.ID)]
		var scope []string
		raw := jsonStr(r.Values["ledger_scope_json"])
		if raw == "" {
			raw = "[]"
		}
		if json.Unmarshal([]byte(raw), &scope) != nil {
			continue
		}
		valid := len(scope) == 0
		for _, l := range scope {
			if l == ledger {
				valid = true
			}
		}
		if valid {
			accounts = append(accounts, a)
		}
	}
	for _, a := range c.Categories {
		if ledgerOf(s.rows[key(EntityCategory, a.ID)]) == ledger {
			a.Path = a.Name
			if parent := jsonStr(s.rows[key(EntityCategory, a.ID)].Values["parent_id"]); parent != "" {
				if pr := s.rows[key(EntityCategory, parent)]; pr != nil && !pr.Deleted && ledgerOf(pr) == ledger && jsonStr(pr.Values["kind"]) == a.Kind {
					prefix := jsonStr(pr.Values["name"]) + "/"
					if !strings.HasPrefix(a.Name, prefix) {
						a.Path = prefix + a.Name
					}
				}
			}
			cats = append(cats, a)
		}
	}
	c.Accounts = accounts
	c.Categories = cats
	return c
}
func (s *Store) BillImportSnapshot(ledger string) (int64, []billimport.Existing, ShortcutCatalog) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := []billimport.Existing{}
	catalog := s.billCatalogLocked(ledger)
	catNames := s.refNamesLocked(EntityCategory)
	acctNames := s.refNamesLocked(EntityAccount)
	for _, r := range s.rows {
		if r.Entity != TxnEntity {
			continue
		}
		v := r.Values
		var evidence json.RawMessage
		text := jsonStr(v["time_evidence_json"])
		if json.Valid([]byte(text)) {
			evidence = json.RawMessage(text)
		}
		category := catNames[jsonStr(v["category_id"])]
		account := acctNames[jsonStr(v["account_id"])]
		to := acctNames[jsonStr(v["to_account_id"])]
		ptr := func(s string) *string {
			if s == "" {
				return nil
			}
			return &s
		}
		row := billimport.Row{OccurredAt: jsonInt(v["occurred_at"]), AmountMinor: jsonInt(v["amount_minor"]), Direction: jsonStr(v["direction"]), Merchant: nullable(v["merchant"]), Note: nullable(v["note"]), Category: ptr(category), Account: ptr(account), ToAccount: ptr(to), Source: jsonStr(v["source"]), ExternalKey: nullable(v["shortcut_external_key"]), TimeEvidence: evidence, ExcludeFromBudget: jsonStr(v["exclude_from_budget"]) == "true"}
		out = append(out, billimport.Existing{ID: r.EntityID, LedgerID: ledgerOf(r), Deleted: r.Deleted, Row: row})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return s.seq, out, catalog
}

type BillImportChoice struct {
	AccountID   string `json:"accountId"`
	ToAccountID string `json:"toAccountId"`
	CategoryID  string `json:"categoryId"`
}
type BillImportCommit struct {
	ActorDeviceID      string                   `json:"-"`
	Revision           int64                    `json:"revision"`
	RememberMappings   bool                     `json:"rememberMappings"`
	Ticket             string                   `json:"ticket"`
	Selected           []int                    `json:"selected"`
	FallbackAccountID  string                   `json:"fallbackAccountId"`
	PreserveCategories bool                     `json:"preserveCategories"`
	AllowUnclassified  bool                     `json:"allowUnclassified"`
	AcknowledgeIssues  bool                     `json:"acknowledgeIssues"`
	Choices            map[int]BillImportChoice `json:"choices"`
}
type BillImportSaved struct {
	Added          int    `json:"added"`
	Restored       int    `json:"restored"`
	Skipped        int    `json:"skipped"`
	Cursor         int64  `json:"cursor"`
	Message        string `json:"message"`
	IncomeMinor    int64  `json:"incomeMinor"`
	ExpenseMinor   int64  `json:"expenseMinor"`
	Remembered     int    `json:"remembered"`
	MappingWarning string `json:"mappingWarning,omitempty"`
}

// CAS + all validation + batch append/fsync under one lock. Parsing never holds the sync lock.
func (s *Store) CommitBillImport(seq int64, ledger string, initial, fresh []billimport.Candidate, o BillImportCommit) (*BillImportSaved, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.seq != seq {
		return nil, ErrImportChanged
	}
	if len(o.Selected) == 0 || len(o.Selected) > 10000 || len(initial) != len(fresh) {
		return nil, errors.New("请先选择要导入的账单")
	}
	catalog := s.billCatalogLocked(ledger)
	found := func(list []ShortcutOption, id, kind string) bool {
		for _, a := range list {
			if a.ID == id && (kind == "" || a.Kind == kind) {
				return true
			}
		}
		return false
	}
	if !found(catalog.Ledgers, ledger, "") {
		return nil, errors.New("请先在手机创建账本并完成同步")
	}
	max := int64(0)
	for _, r := range s.rows {
		if r.Lamport > max {
			max = r.Lamport
		}
	}
	if max > math.MaxInt64-int64(len(o.Selected)*3+1) {
		return nil, errors.New("同步版本计数达到上限")
	}
	changes := []merge.Change{}
	made := map[string]string{}
	seen := map[int]bool{}
	skipped := 0
	change := func(entity, id string, values map[string]json.RawMessage) {
		max++
		// Match the existing Android Map<String,String> wire representation:
		// raw JSON text is itself transported as a string, including literal null.
		// No floating-point decoding and no change to the raw-byte merge layer.
		wire := make(map[string]json.RawMessage, len(values))
		for field, value := range values {
			wire[field] = merge.V(string(value))
		}
		changes = append(changes, merge.Change{ChangeID: "nas-bill-import:" + o.Ticket + ":" + id, Entity: entity, EntityID: id, Op: merge.OpUpsert, Data: wire, Lamport: max, Origin: "nas-bill-import"})
	}
	category := func(path, kind string) (string, error) {
		parts := strings.Split(path, "/")
		if len(parts) > 2 {
			return "", errors.New("原分类层级超过两级，请调整分类")
		}
		parent := ""
		for _, name := range parts {
			name = strings.TrimSpace(name)
			if name == "" || utf8.RuneCountInString(name) > 60 {
				return "", errors.New("原分类名称无效")
			}
			k := ledger + "\x00" + kind + "\x00" + parent + "\x00" + name
			if id := made[k]; id != "" {
				parent = id
				continue
			}
			ids := []string{}
			for _, r := range s.rows {
				if r.Entity == EntityCategory && !r.Deleted && ledgerOf(r) == ledger && jsonStr(r.Values["kind"]) == kind && jsonStr(r.Values["name"]) == name && jsonStr(r.Values["parent_id"]) == parent {
					ids = append(ids, r.EntityID)
				}
			}
			if len(ids) > 1 {
				return "", errors.New("原分类重名，请先在手机分类管理中核对")
			}
			id := ""
			if len(ids) == 1 {
				id = ids[0]
			} else {
				sum := sha256.Sum256([]byte(o.Ticket + "/category/" + k))
				id = "nas-cat-" + hex.EncodeToString(sum[:])
				var parentValue any
				if parent != "" {
					parentValue = parent
				}
				change(EntityCategory, id, map[string]json.RawMessage{"name": merge.V(name), "kind": merge.V(kind), "parent_id": merge.V(parentValue), "ledger_id": merge.V(ledger), "sort_order": merge.V(1000 + len(made)), "icon": merge.V(nil)})
			}
			made[k] = id
			parent = id
		}
		return parent, nil
	}
	seenID := map[string]bool{}
	added := 0
	restored := 0
	var incomeMinor, expenseMinor int64
	learned := map[string]map[string]bool{}
	addAmount := func(r billimport.Row) error {
		if r.ToAccount != nil {
			return nil
		}
		target := &expenseMinor
		if r.Direction == DirIncome {
			target = &incomeMinor
		}
		if r.AmountMinor > math.MaxInt64-*target {
			return errors.New("导入汇总金额过大")
		}
		*target += r.AmountMinor
		return nil
	}
	for _, index := range o.Selected {
		if index < 0 || index >= len(fresh) || seen[index] {
			return nil, errors.New("导入选择无效")
		}
		seen[index] = true
		c := fresh[index]
		r := c.Row
		if c.Match == "DUPLICATE" || c.Match == "SUSPECTED" && initial[index].Match == "NEW" {
			skipped++
			continue
		}
		if c.Match == "DELETED" {
			old := s.rows[key(TxnEntity, c.ExistingID)]
			if initial[index].Match != "DELETED" || initial[index].ExistingID != c.ExistingID ||
				old == nil || !old.Deleted || ledgerOf(old) != ledger || seenID[c.ExistingID] {
				skipped++
				continue
			}
			seenID[c.ExistingID] = true
			max++
			// Preserve the original wire bytes and every user edit; a newer unchanged content
			// stamp restores the same identity under the normal tombstone merge rule.
			changes = append(changes, merge.Change{ChangeID: "nas-bill-import:" + o.Ticket + ":" + c.ExistingID,
				Entity: TxnEntity, EntityID: c.ExistingID, Op: merge.OpUpsert,
				Data: map[string]json.RawMessage{"amount_minor": old.Values["amount_minor"]}, Lamport: max, Origin: "nas-bill-import"})
			if err := addAmount(billimport.Row{AmountMinor: jsonInt(old.Values["amount_minor"]), Direction: jsonStr(old.Values["direction"]), ToAccount: nullable(old.Values["to_account_id"])}); err != nil {
				return nil, err
			}
			restored++
			continue
		}
		if initial[index].Match == "DELETED" {
			skipped++
			continue
		}
		if r.AmountMinor <= 0 || r.AmountMinor > 99999999999 || r.OccurredAt <= 0 || r.Direction != DirExpense && r.Direction != DirIncome {
			return nil, errors.New("账单金额、时间或方向无效")
		}
		a := c.AccountID
		if o.Choices[index].AccountID != "" {
			a = o.Choices[index].AccountID
		}
		if a == "" {
			a = o.FallbackAccountID
		}
		if !found(catalog.Accounts, a, "") {
			return nil, errors.New("请为未匹配账单选择人民币账户")
		}
		to := c.ToAccountID
		if o.Choices[index].ToAccountID != "" {
			to = o.Choices[index].ToAccountID
		}
		if r.ToAccount != nil && (!found(catalog.Accounts, to, "") || a == to) {
			return nil, errors.New("请核对划转的转出、转入账户，不能相同")
		}
		id := c.ImportID
		if id == "" {
			sum := sha256.Sum256([]byte(o.Ticket + "/row/" + strconv.Itoa(index)))
			id = "nas-bill-" + hex.EncodeToString(sum[:])
		}
		if seenID[id] || s.rows[key(TxnEntity, id)] != nil {
			skipped++
			continue
		}
		seenID[id] = true
		cat := c.CategoryID
		if manual := o.Choices[index].CategoryID; manual != "" {
			if r.ToAccount != nil {
				return nil, errors.New("划转不使用收支分类")
			}
			cat = manual
		}
		if cat != "" && !found(catalog.Categories, cat, r.Direction) {
			return nil, errors.New("分类已变化，请重新预览")
		}
		if cat == "" && r.Category != nil {
			if o.PreserveCategories && r.Source != "BILL_ALIPAY" && r.Source != "BILL_WECHAT" && r.Source != "BILL_BANK_ICBC" && r.Source != "BILL_BANK_TABLE" {
				var err error
				cat, err = category(*r.Category, r.Direction)
				if err != nil {
					return nil, err
				}
			} else if !o.AllowUnclassified {
				return nil, errors.New("原分类未匹配，请选择保留原分类或允许未分类")
			}
		}
		var toValue, catValue, evidence any
		if r.ToAccount != nil {
			toValue = to
		}
		if cat != "" {
			catValue = cat
		}
		if len(r.TimeEvidence) > 0 && string(r.TimeEvidence) != "null" {
			evidence = string(r.TimeEvidence)
		}
		source := r.Source
		if source == "MANUAL" || source == "" {
			source = "IMPORT"
		}
		change(TxnEntity, id, map[string]json.RawMessage{"ledger_id": merge.V(ledger), "amount_minor": merge.V(r.AmountMinor), "direction": merge.V(r.Direction), "account_id": merge.V(a), "to_account_id": merge.V(toValue), "refund_of_id": merge.V(nil), "tags_json": merge.V("[]"), "category_id": merge.V(catValue), "occurred_at": merge.V(r.OccurredAt), "merchant": merge.V(r.Merchant), "note": merge.V(r.Note), "source": merge.V(source), "confidence": merge.V("0.8"), "shortcut_external_key": merge.V(r.ExternalKey), "time_evidence_json": merge.V(evidence), "exclude_from_budget": merge.V(r.ExcludeFromBudget)})
		if err := addAmount(r); err != nil {
			return nil, err
		}
		if o.RememberMappings && o.Choices[index].CategoryID != "" {
			if k := ImportCategoryKey(r); k != "" {
				if learned[k] == nil {
					learned[k] = map[string]bool{}
				}
				learned[k][cat] = true
			}
		}
		added++
	}
	if len(changes) > 0 {
		actor := o.ActorDeviceID
		if actor == "" {
			actor = "nas-bill-import"
		}
		res, err := s.pushLocked(actor, changes)
		if err != nil {
			return nil, err
		}
		if len(res.Rejected) > 0 {
			return nil, errors.New("同步保存被拒绝")
		}
	}
	remembered, warning := s.rememberImportMappingsLocked(ledger, learned)
	return &BillImportSaved{Added: added, Restored: restored, Skipped: skipped, Cursor: s.seq, IncomeMinor: incomeMinor, ExpenseMinor: expenseMinor, Remembered: remembered, MappingWarning: warning, Message: "已保存到 NAS 同步队列。手机下次同步后可见；如需立即查看，在手机 NAS 同步页点“立即同步”。"}, nil
}
