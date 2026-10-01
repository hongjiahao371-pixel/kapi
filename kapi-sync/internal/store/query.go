// Package-level read model for the NAS panel.
//
// store.go exists to make SYNC correct; nothing in it was built to answer
// "how much did I spend on 餐饮 in August". This file is that, and it is
// deliberately separate: every type here is derived on read and thrown away,
// so adding a field to the panel can never change what gets synced or how it
// merges.
//
// # Why the values are unquoted JSON, and why that is annoying
//
// The transport contract (see merge.Record) is that `Values` holds **raw JSON
// text** per field, byte-for-byte. So a row looks like this on disk:
//
//	"amount_minor": 45900          <- bare number
//	"direction":    "EXPENSE"      <- quoted string, quotes included in the bytes
//	"note":         "午饭"
//
// `json.Unmarshal` into an int64 handles the first shape; into a string it
// handles the second. What it does NOT handle is a field that is simply
// absent, or a row written by a future client that adds a field we do not know
// about. Both must degrade to "empty", never to a panic and never to a wrong
// number — see [readRecord].
package store

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"kapi/sync/internal/merge"
)

// TxnEntity is the only entity the phone currently syncs. It is a constant
// here for the same reason it is a literal in SyncEngine.kt: one writer, one
// spelling, and a typo must be a compile error rather than an empty panel.
const TxnEntity = "txn"

// Direction values. Anything else is treated as EXPENSE by the *client*, so
// the panel mirrors that rather than inventing a third bucket — see
// [TxView.SignedMinor], which is the only place direction is interpreted.
const (
	DirExpense = "EXPENSE"
	DirIncome  = "INCOME"
)

// 参与同步的实体。txn 之外的这两张是**参考数据**：账目里的 category_id /
// account_id 指向它们，少同步任何一张，面板上就是一列 "cat-1" 而不是"交通"。
// TxnEntity 已经在上面定义过了。
const (
	EntityCategory = "category"
	EntityAccount  = "account"
)

// TxView is one transaction shaped for display.
type TxView struct {
	ID          string `json:"id"`
	AmountMinor int64  `json:"amount_minor"`
	Direction   string `json:"direction"`
	AccountID   string `json:"account_id"`
	ToAccountID string `json:"to_account_id,omitempty"`
	CategoryID  string `json:"category_id,omitempty"`
	// CategoryName and AccountName are the *display* names, resolved against
	// the synced category/account tables. They are additive on purpose: the
	// raw ids stay in the payload so the panel can still show something (and
	// filter by id) when a transaction points at a row that has not arrived
	// yet — which is exactly what happens between the first push of a txn and
	// the first push of its category.
	CategoryName string `json:"category_name,omitempty"`
	AccountName  string `json:"account_name,omitempty"`
	// OccurredAt is epoch millis, matching Entities.kt. Never used for merge
	// ordering — it is user-editable — only for display and filtering.
	OccurredAt int64  `json:"occurred_at"`
	Note       string `json:"note,omitempty"`
	Merchant   string `json:"merchant,omitempty"`
	Deleted    bool   `json:"deleted"`
	Lamport    int64  `json:"lamport"`
}

// SignedMinor is the number the panel actually adds up: expenses negative,
// income positive. Doing the sign here rather than in JavaScript means the
// monthly totals on the server and the ones in the browser cannot disagree.
func (t TxView) SignedMinor() int64 {
	if t.Direction == DirIncome {
		return t.AmountMinor
	}
	return -t.AmountMinor
}

// TxQuery is the panel's filter. Every field is optional; the zero value means
// "no constraint on this dimension".
type TxQuery struct {
	From, To   int64 // occurred_at, inclusive lower / exclusive upper, millis
	Keyword    string
	Direction  string
	CategoryID string
	AccountID  string
	// IncludeDeleted shows tombstoned rows. Off by default: a deleted row is
	// a row the user asked to forget, and a ledger that still lists it looks
	// broken. The panel exposes a toggle because "I deleted it by accident" is
	// a real support question and the server already has the tombstones.
	IncludeDeleted bool
	Offset, Limit  int
}

// TxPage is one page of results plus the unpaginated total, which the panel
// needs in order to say "共 N 笔" without a second round trip.
type TxPage struct {
	Rows  []TxView `json:"rows"`
	Total int      `json:"total"`
	// Offset and Limit echo back what was actually applied, after clamping.
	// The panel would otherwise render "showing 1-500 of 12,000" using the
	// numbers it asked for rather than the ones it got.
	Offset int `json:"offset"`
	Limit  int `json:"limit"`
}

const (
	defaultLimit = 100
	maxLimit     = 1000
)

// ListTx applies q and returns a page.
func (s *Store) ListTx(q TxQuery) TxPage {
	if q.Limit <= 0 {
		q.Limit = defaultLimit
	}
	if q.Limit > maxLimit {
		q.Limit = maxLimit
	}
	if q.Offset < 0 {
		q.Offset = 0
	}
	// 方向只认 EXPENSE/INCOME。其余一律当成"不过滤"，而不是"匹配不到任何行"：
	// 一个手改过的 URL（或者将来某个客户端写了第三个值）不该让整个列表空掉，
	// 那看起来就像账丢了。归一放在 store 而不是只在 HTTP 层，是为了让直接调用
	// ListTx 的代码也拿到同样的语义。
	if q.Direction != DirExpense && q.Direction != DirIncome {
		q.Direction = ""
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	catNames := s.refNamesLocked(EntityCategory)
	acctNames := s.refNamesLocked(EntityAccount)

	out := make([]TxView, 0, q.Limit)
	for _, r := range s.rows {
		// **必须按实体过滤。** rows 是所有实体共用的表：分类和账户也是行。
		// 只有 txn 参与加总——把 17 条分类行当成 17 笔 0 分的支出，
		// 面板上就是四行"现金/银行卡/支付宝/微信"，每行 0.00 元。
		// 这个 bug 在只有账单参与同步的时候不可能出现。
		if r.Entity != TxnEntity {
			continue
		}
		if r.Deleted && !q.IncludeDeleted {
			continue
		}
		v := readRecord(r)
		if !q.match(v) {
			continue
		}
		// 名字按行补，id 原样留着。match() 已经在**原始 id** 上筛过了，
		// 这里换成显示名不会影响筛选——否则用户选了"交通"却筛不出账。
		v.CategoryName = resolveName(catNames, v.CategoryID)
		v.AccountName = resolveName(acctNames, v.AccountID)
		out = append(out, v)
	}

	// Newest first, and the tiebreak must be total or two rows with the same
	// occurred_at swap places between page loads — which reads as "the app is
	// randomly reordering my ledger". Lamport then id gives a total order
	// that does not depend on map iteration.
	sort.Slice(out, func(i, j int) bool {
		if out[i].OccurredAt != out[j].OccurredAt {
			return out[i].OccurredAt > out[j].OccurredAt
		}
		if out[i].Lamport != out[j].Lamport {
			return out[i].Lamport > out[j].Lamport
		}
		return out[i].ID < out[j].ID
	})

	total := len(out)
	start := q.Offset
	if start > total {
		start = total
	}
	end := start + q.Limit
	if end > total {
		end = total
	}
	return TxPage{Rows: out[start:end], Total: total, Offset: q.Offset, Limit: q.Limit}
}

func (q TxQuery) match(t TxView) bool {
	if q.From > 0 && t.OccurredAt < q.From {
		return false
	}
	if q.To > 0 && t.OccurredAt >= q.To {
		return false
	}
	if q.Direction != "" && t.Direction != q.Direction {
		return false
	}
	if q.CategoryID != "" && t.CategoryID != q.CategoryID {
		return false
	}
	if q.AccountID != "" && t.AccountID != q.AccountID {
		return false
	}
	if q.Keyword == "" {
		return true
	}
	kw := strings.ToLower(q.Keyword)
	// 搜索商户、备注、分类、账户、ID —— 用户会拿任何一个他看得见的字段来搜。
	for _, hay := range []string{t.Merchant, t.Note, t.CategoryID, t.AccountID, t.ID} {
		if hay != "" && strings.Contains(strings.ToLower(hay), kw) {
			return true
		}
	}
	// 金额也要能搜，而且要同时认"分"和"元"两种写法。用户看到的是 ¥459.00，
	// 机器存的是 45900；只认其中一种，搜 "459" 就搜不到那唯一一笔 459 块的消费，
	// 面板会被当成坏了。三个形态都放进候选里，代价是几行。
	mag := t.AmountMinor
	if mag < 0 {
		mag = -mag
	}
	for _, cand := range []string{
		strconv.FormatInt(t.AmountMinor, 10),
		strconv.FormatInt(mag, 10),
		strconv.FormatInt(mag/100, 10),
		fmt.Sprintf("%d.%02d", mag/100, mag%100),
	} {
		if strings.Contains(cand, kw) {
			return true
		}
	}
	return false
}

// Sum is one bucket of a breakdown.
type Sum struct {
	Key   string `json:"key"`
	Minor int64  `json:"minor"`
	Count int    `json:"count"`
}

// Stats is the summary the panel header shows.
type Stats struct {
	Count   int   `json:"count"`
	Income  int64 `json:"income"`
	Expense int64 `json:"expense"`
	// Balance is income - expense, signed the same way as TxView.SignedMinor.
	Balance int64 `json:"balance"`
	// ByCategory is expenses only. Mixing income into a category breakdown
	// makes "餐饮 ¥300" ambiguous when someone refunds a meal.
	ByCategory []Sum `json:"by_category"`
	// ByMonth is the last 24 buckets that have any data, oldest first.
	ByMonth []Sum `json:"by_month"`
}

// Stats computes the summary over the same filter the list uses, so the header
// total and the list can never describe different populations.
func (s *Store) Stats(q TxQuery) Stats {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var st Stats
	byCat := map[string]*Sum{}
	byMonth := map[string]*Sum{}
	// 分类名在这里解析一次，而不是每行都去查表。
	catNames := s.refNamesLocked(EntityCategory)

	for _, r := range s.rows {
		if r.Entity != TxnEntity {
			continue // 只有账目进汇总，见 ListTx 里同样的说明
		}
		if r.Deleted {
			continue // totals never include tombstones, even if the list does
		}
		t := readRecord(r)
		if !q.match(t) {
			continue
		}
		st.Count++
		signed := t.SignedMinor()
		if signed >= 0 {
			st.Income += signed
		} else {
			st.Expense += -signed
			if t.CategoryID != "" {
				// 按显示名聚合，不是按 id。同一个分类被两台手机用不同 id 建出来时，
				// 按 id 聚合会算成两行——"交通 ¥300"和"交通 ¥200"，
				// 而用户只想知道交通一共花了多少。
				name := resolveName(catNames, t.CategoryID)
				b := byCat[name]
				if b == nil {
					b = &Sum{Key: name}
					byCat[name] = b
				}
				b.Minor += -signed
				b.Count++
			}
		}
		// Month key in the server's local zone. The phone writes epoch millis
		// with no zone attached, so "which month" is genuinely undefined until
		// somebody picks a zone; the NAS and the phone are on the same LAN and
		// normally the same wall clock, and this is the only place that choice
		// is made.
		mk := monthKey(t.OccurredAt)
		b := byMonth[mk]
		if b == nil {
			b = &Sum{Key: mk}
			byMonth[mk] = b
		}
		b.Minor += signed
		b.Count++
	}
	st.Balance = st.Income - st.Expense

	st.ByCategory = sortedSums(byCat)
	st.ByMonth = sortedSums(byMonth)
	if len(st.ByMonth) > 24 {
		st.ByMonth = st.ByMonth[len(st.ByMonth)-24:]
	}
	return st
}

func sortedSums(m map[string]*Sum) []Sum {
	out := make([]Sum, 0, len(m))
	for _, v := range m {
		out = append(out, *v)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Key != out[j].Key {
			return out[i].Key < out[j].Key
		}
		return out[i].Minor < out[j].Minor
	})
	return out
}

// Facet is one distinct value of a filterable dimension.
type Facet struct {
	Key   string `json:"key"`
	Count int    `json:"count"`
}

// Facets returns the distinct values of a dimension, for the panel's filter
// dropdowns. It deliberately ignores the row's own filters except the
// dimension being faceted: narrowing the category list to the currently
// selected category would make the dropdown useless for switching away.
func (s *Store) Facets(dimension string) []Facet {
	s.mu.RLock()
	defer s.mu.RUnlock()

	counts := map[string]int{}
	catNames := s.refNamesLocked(EntityCategory)
	acctNames := s.refNamesLocked(EntityAccount)
	for _, r := range s.rows {
		if r.Entity != TxnEntity {
			continue // 分类/账户行不参与任何账目维度的分面
		}
		if r.Deleted {
			continue
		}
		t := readRecord(r)
		var key string
		switch dimension {
		case "category":
			key = resolveName(catNames, t.CategoryID)
		case "account":
			key = resolveName(acctNames, t.AccountID)
		case "merchant":
			key = t.Merchant
		default:
			return nil
		}
		if key != "" {
			counts[key]++
		}
	}
	out := make([]Facet, 0, len(counts))
	for k, v := range counts {
		out = append(out, Facet{Key: k, Count: v})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Count != out[j].Count {
			return out[i].Count > out[j].Count
		}
		return out[i].Key < out[j].Key
	})
	return out
}

// readRecord projects a merge record into a display view.
//
// Every accessor degrades to the zero value on malformed input. A panel that
// 500s because one row has a field the current build does not understand is
// worse than a panel that shows that row with a blank note: the phone will
// re-push the field and the panel will start rendering it after an update,
// without a migration.
// refNamesLocked builds an id → display-name index from the reference tables
// (category / account).
//
// **The caller must already hold s.mu.** Every caller here is a read path that
// takes RLock once at the top; re-locking inside would deadlock, and Go's
// sync.RWMutex is not reentrant — that failure is a hard hang, not a panic.
//
// Why this exists: a transaction stores a category_id, and the id is an opaque
// string the phone invented ("cat-1"). Showing it raw means the panel says
// "cat-1" where the phone says "交通". The reference rows are in the same store
// because the phone syncs them alongside the transactions.
//
// A row with no name is skipped rather than mapped to "": an empty map entry
// would resolve to an empty label, which reads as a rendering bug.
func (s *Store) refNamesLocked(entity string) map[string]string {
	out := map[string]string{}
	for _, r := range s.rows {
		if r.Deleted || r.Entity != entity {
			continue
		}
		if n := jsonStr(r.Values["name"]); n != "" {
			out[r.EntityID] = n
		}
	}
	return out
}

// resolveName returns the display name, or the id itself when the reference
// row has not arrived. Falling back to the id is deliberate: an unresolved
// "cat-1" is ugly but honest, whereas dropping the label entirely would leave
// the user wondering whether the transaction has no category at all.
func resolveName(names map[string]string, id string) string {
	if id == "" {
		return ""
	}
	if n, ok := names[id]; ok {
		return n
	}
	return id
}

func readRecord(r *merge.Record) TxView {
	return TxView{
		ID:          r.EntityID,
		AmountMinor: jsonInt(r.Values["amount_minor"]),
		Direction:   jsonStr(r.Values["direction"]),
		AccountID:   jsonStr(r.Values["account_id"]),
		ToAccountID: jsonStr(r.Values["to_account_id"]),
		CategoryID:  jsonStr(r.Values["category_id"]),
		OccurredAt:  jsonInt(r.Values["occurred_at"]),
		Note:        jsonStr(r.Values["note"]),
		Merchant:    jsonStr(r.Values["merchant"]),
		Deleted:     r.Deleted,
		Lamport:     r.Lamport,
	}
}

// jsonStr 解出字符串值，并且**反复剥引号**。
//
// 只剥一层是不够的，而且这个 bug 不会报错：真实安卓客户端把字符串包了两次
// （SyncE2ETest 里 jsonStr(direction) 产出一层，SyncEngine 序列化时又包一层），
// 服务端最终拿到的是 "\"EXPENSE\""。剥一层剩下带引号的 "EXPENSE"，
// 于是 t.Direction 永远不等于 DirExpense/DirIncome —— SignedMinor 把每一笔都
// 当成支出，一笔 66 块的收入进了支出合计，结余跟着错，而面板看上去一切正常。
//
// 这个坑是拿真机跑端到端测试时看见的：用 Go 的 merge.V() 造的数据只有一层
// 引号，单测全绿；换成真客户端推上来的数据才暴露。所以循环上限写死 3 层
// （再多层就是另一个 bug 了），并且遇到不再是 JSON 字符串就停。
func jsonStr(raw json.RawMessage) string {
	s := string(raw)
	for i := 0; i < 3; i++ {
		var out string
		if err := json.Unmarshal([]byte(s), &out); err != nil {
			break
		}
		s = out
	}
	return s
}

func jsonInt(raw json.RawMessage) int64 {
	if len(raw) == 0 {
		return 0
	}
	var n int64
	if err := json.Unmarshal(raw, &n); err == nil {
		return n
	}
	// Some older clients wrote numbers as strings. Accepting that costs three
	// lines and saves a panel that shows ¥0 for a month of real spending.
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		if n2, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64); err == nil {
			return n2
		}
	}
	return 0
}

// monthKey buckets an epoch-millis timestamp into "2026-09".
//
// The server's local zone is used, not UTC. A transaction at 2026-10-01 00:30
// Beijing time is October 1st to the person who made it and September 30th in
// UTC; showing it under the wrong month is the kind of bug that makes a
// spending report untrustworthy. The phone writes no zone, so the only honest
// choice is to interpret it where the data is being read.
func monthKey(ms int64) string {
	if ms <= 0 {
		return ""
	}
	t := time.UnixMilli(ms).Local()
	return t.Format("2006-01")
}
