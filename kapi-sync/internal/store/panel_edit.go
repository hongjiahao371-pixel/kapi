package store

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"kapi/sync/internal/merge"
	"math"
	"regexp"
	"strings"
	"unicode/utf8"
)

var ErrEditConflict = errors.New("这笔账单已被其他设备修改，请重新打开并核对后再保存")
var ErrEditNotFound = errors.New("账单已删除或不存在，不能编辑")
var ErrEditRequestReuse = errors.New("本次保存编号已使用，请重新打开编辑页")
var editRequestID = regexp.MustCompile(`^[A-Za-z0-9_-]{16,80}$`)

type TransactionEdit struct {
	ID          string `json:"id"`
	Revision    string `json:"revision"`
	RequestID   string `json:"request_id"`
	AmountMinor int64  `json:"amount_minor"`
	CategoryID  string `json:"category_id"`
	AccountID   string `json:"account_id"`
	Note        string `json:"note"`
}
type TransactionEditor struct {
	Record   *merge.Record   `json:"record"`
	Revision string          `json:"revision"`
	Catalog  ShortcutCatalog `json:"catalog"`
	CanEdit  bool            `json:"can_edit"`
}
type TransactionEditSaved struct {
	Record    *merge.Record `json:"record"`
	Revision  string        `json:"revision"`
	Cursor    int64         `json:"cursor"`
	Replayed  bool          `json:"replayed"`
	Unchanged bool          `json:"unchanged"`
}

func editHash(value any) string {
	b, _ := json.Marshal(value)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func editActor(actor string) string {
	if actor == "" {
		return "nas-panel-edit"
	}
	return actor
}
func (s *Store) editorAllowedLocked(actor string, r *merge.Record) bool {
	if r == nil || r.Deleted {
		return false
	}
	if s.writeGuard == nil {
		return true
	}
	_, err := s.writeGuard(editActor(actor), r, merge.Change{Entity: TxnEntity, EntityID: r.EntityID, Op: merge.OpUpsert, Data: map[string]json.RawMessage{}, Lamport: r.Lamport, Origin: editActor(actor)})
	return err == nil
}
func (s *Store) TransactionEditor(id, actor string) (*TransactionEditor, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	r := s.rows[key(TxnEntity, id)]
	if r == nil {
		return nil, ErrEditNotFound
	}
	catalog := s.billCatalogLocked(ledgerOf(r))
	// Existing inactive references may be retained, but not newly selected.
	accounts := catalog.Accounts[:0]
	for _, a := range catalog.Accounts {
		row := s.rows[key(EntityAccount, a.ID)]
		if jsonStr(row.Values["hidden"]) != "true" {
			accounts = append(accounts, a)
		}
	}
	catalog.Accounts = accounts
	return &TransactionEditor{r.Clone(), editHash(r), catalog, s.editorAllowedLocked(actor, r)}, nil
}

// Compare-and-save, validation, field version allocation and fsync share the sync
// lock. Same entity ID and immutable receipt fields survive every retry/restart.
func (s *Store) EditTransaction(actor string, e TransactionEdit) (*TransactionEditSaved, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	actor = editActor(actor)
	r := s.rows[key(TxnEntity, e.ID)]
	if r == nil || r.Deleted {
		return nil, ErrEditNotFound
	}
	if !s.editorAllowedLocked(actor, r) {
		return nil, ErrWriteForbidden
	}
	if !editRequestID.MatchString(e.RequestID) {
		return nil, errors.New("保存编号无效，请重新打开编辑页")
	}
	prefix := "nas-edit/" + editHash(actor+"\x00"+e.RequestID) + "/"
	changeID := prefix + editHash(e)
	for i := len(s.log) - 1; i >= 0; i-- {
		entry := s.log[i]
		if strings.HasPrefix(entry.ChangeID, prefix) {
			if entry.ChangeID != changeID || entry.EntityID != e.ID {
				return nil, ErrEditRequestReuse
			}
			return &TransactionEditSaved{Record: r.Clone(), Revision: editHash(r), Cursor: s.seq, Replayed: true}, nil
		}
	}
	if e.Revision == "" || e.Revision != editHash(r) {
		return nil, ErrEditConflict
	}
	if e.AmountMinor < 1 || e.AmountMinor > 99_999_999_999 {
		return nil, errors.New("金额必须大于 0，且不超过 999999999.99 元")
	}
	if !utf8.ValidString(e.Note) || utf8.RuneCountInString(e.Note) > 2000 {
		return nil, errors.New("备注最多 2000 字")
	}
	v := readRecord(r)
	ledger := ledgerOf(r)
	if v.Direction != DirExpense && v.Direction != DirIncome {
		return nil, errors.New("账单收支类型异常，请先在手机核对")
	}
	if e.AccountID != v.AccountID {
		valid := false
		for _, a := range s.billCatalogLocked(ledger).Accounts {
			if a.ID == e.AccountID {
				row := s.rows[key(EntityAccount, a.ID)]
				valid = jsonStr(row.Values["hidden"]) != "true"
			}
		}
		if !valid {
			return nil, errors.New("请选择适用于本账本的有效人民币账户")
		}
	}
	if e.AccountID == "" || e.AccountID == v.ToAccountID {
		return nil, errors.New("转出与转入账户不能相同，且应选择有效账户")
	}
	if e.CategoryID != v.CategoryID && e.CategoryID != "" {
		cat := s.rows[key(EntityCategory, e.CategoryID)]
		if cat == nil || cat.Deleted || ledgerOf(cat) != ledger || jsonStr(cat.Values["kind"]) != v.Direction {
			return nil, errors.New("分类与账本或收支类型不符")
		}
	}
	if v.ToAccountID != "" && e.CategoryID != v.CategoryID {
		return nil, errors.New("账户划转不参与收支分类")
	}
	// Preserve the same refund constraints as phone editing, including all sibling refunds.
	refundTotal := int64(0)
	siblings := int64(0)
	for _, row := range s.rows {
		if row.Entity != TxnEntity || row.Deleted {
			continue
		}
		t := readRecord(row)
		if t.RefundOfID == e.ID {
			if t.AmountMinor < 0 || refundTotal > math.MaxInt64-t.AmountMinor {
				return nil, errors.New("退款金额异常")
			}
			refundTotal += t.AmountMinor
		}
		if v.RefundOfID != "" && t.RefundOfID == v.RefundOfID && t.ID != e.ID {
			if t.AmountMinor < 0 || siblings > math.MaxInt64-t.AmountMinor {
				return nil, errors.New("退款金额异常")
			}
			siblings += t.AmountMinor
		}
	}
	if refundTotal > 0 && (v.Direction != DirExpense || e.AmountMinor < refundTotal) {
		return nil, errors.New("金额不能小于已到账退款")
	}
	if v.RefundOfID != "" {
		original := s.rows[key(TxnEntity, v.RefundOfID)]
		if original == nil || original.Deleted || v.Direction != DirIncome || siblings > jsonInt(original.Values["amount_minor"]) || e.AmountMinor > jsonInt(original.Values["amount_minor"])-siblings {
			return nil, errors.New("退款不能超过原消费的剩余可退金额")
		}
	}
	data := map[string]json.RawMessage{}
	if e.AmountMinor != v.AmountMinor {
		data["amount_minor"] = merge.V(e.AmountMinor)
	}
	if e.AccountID != v.AccountID {
		data["account_id"] = merge.V(e.AccountID)
	}
	if e.CategoryID != v.CategoryID {
		if e.CategoryID == "" {
			data["category_id"] = merge.V(nil)
		} else {
			data["category_id"] = merge.V(e.CategoryID)
		}
		data["source"] = merge.V("MANUAL")
	}
	if e.Note != v.Note {
		if e.Note == "" {
			data["note"] = merge.V(nil)
		} else {
			data["note"] = merge.V(e.Note)
		}
	}
	if len(data) == 0 {
		return &TransactionEditSaved{Record: r.Clone(), Revision: editHash(r), Cursor: s.seq, Unchanged: true}, nil
	}
	max := int64(0)
	for _, row := range s.rows {
		if row.Lamport > max {
			max = row.Lamport
		}
	}
	if max == math.MaxInt64 {
		return nil, errors.New("同步版本计数达到上限")
	}
	change := merge.Change{ChangeID: changeID, Entity: TxnEntity, EntityID: e.ID, Op: merge.OpUpsert, Data: data, Lamport: max + 1, Origin: actor}
	result, err := s.pushLocked(actor, []merge.Change{change})
	if err != nil {
		return nil, err
	}
	if len(result.Rejected) > 0 {
		return nil, errors.New("同步保存被拒绝，请重新核对账单")
	}
	saved := s.rows[key(TxnEntity, e.ID)]
	return &TransactionEditSaved{Record: saved.Clone(), Revision: editHash(saved), Cursor: result.Cursor}, nil
}
