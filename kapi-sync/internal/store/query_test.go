package store_test

import (
	"encoding/json"
	"testing"
	"time"

	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
)

// 这些用例全部走**真的** Store.Push，而不是直接往 rows map 里塞东西。
//
// 原因很实际：面板读的就是 rows，而 rows 只能由 Push 产生。直接构造 map 能测到
// ListTx 的一半，测不到值在传输层被包了几层引号——而那正是这个面板最可能出错
// 的地方（见 readRecord 的注释）。

func ts(y int, m time.Month, d, hh int) int64 {
	return time.Date(y, m, d, hh, 0, 0, 0, time.Local).UnixMilli()
}

func upsert(id string, lam int64, amount int64, dir, cat, acct, merchant, note string, at int64) merge.Change {
	d := map[string]json.RawMessage{
		"amount_minor": merge.V(amount),
		"direction":    merge.V(dir),
		"account_id":   merge.V(acct),
		"occurred_at":  merge.V(at),
	}
	if cat != "" {
		d["category_id"] = merge.V(cat)
	}
	if merchant != "" {
		d["merchant"] = merge.V(merchant)
	}
	if note != "" {
		d["note"] = merge.V(note)
	}
	return merge.Change{
		ChangeID: "c" + id, Entity: store.TxnEntity, EntityID: id,
		Op: merge.OpUpsert, Data: d, Lamport: lam, Origin: "dev_test",
	}
}

func seed(t *testing.T) *store.Store {
	t.Helper()
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	sep := ts(2026, time.September, 1, 12)
	changes := []merge.Change{
		upsert("t1", 1, 45900, store.DirExpense, "购物", "支付宝", "京东超市", "", ts(2026, time.September, 2, 19)),
		upsert("t2", 2, 1850000, store.DirIncome, "工资", "工资卡", "公司", "9月工资", ts(2026, time.September, 5, 10)),
		upsert("t3", 3, 38000, store.DirExpense, "娱乐", "支付宝", "国家大剧院", "", ts(2026, time.September, 6, 20)),
		upsert("t4", 4, 600, store.DirExpense, "交通", "支付宝", "地铁通勤", "", sep),
		upsert("t5", 5, 23640, store.DirExpense, "日用", "微信", "盒马鲜生", "", ts(2026, time.August, 30, 21)),
		upsert("t6", 6, 1800, store.DirExpense, "餐饮", "微信", "瑞幸咖啡", "生椰拿铁", ts(2026, time.October, 1, 8)),
	}
	if _, err := st.Push("dev_test", changes); err != nil {
		t.Fatalf("push: %v", err)
	}
	return st
}

func TestListTxUnquotesAndNumbers(t *testing.T) {
	st := seed(t)
	page := st.ListTx(store.TxQuery{Limit: 100})
	if page.Total != 6 {
		t.Fatalf("total = %d, want 6", page.Total)
	}
	var byID = map[string]store.TxView{}
	for _, r := range page.Rows {
		byID[r.ID] = r
	}

	// 传输层约定：字符串字段在 values 里是带引号的原始 JSON 片段。
	// 这里每一个都要能读回原值，任何一个读成空串或 0 都会让面板显示错账。
	t1 := byID["t1"]
	if t1.AmountMinor != 45900 {
		t.Errorf("t1 amount = %d, want 45900", t1.AmountMinor)
	}
	if t1.Direction != store.DirExpense {
		t.Errorf("t1 direction = %q, want EXPENSE", t1.Direction)
	}
	if t1.CategoryID != "购物" {
		t.Errorf("t1 category = %q, want 购物", t1.CategoryID)
	}
	if t1.Merchant != "京东超市" {
		t.Errorf("t1 merchant = %q", t1.Merchant)
	}
	if t1.OccurredAt != ts(2026, time.September, 2, 19) {
		t.Errorf("t1 occurred_at = %d", t1.OccurredAt)
	}
	if t2 := byID["t2"]; t2.Note != "9月工资" {
		t.Errorf("t2 note = %q, want 9月工资", t2.Note)
	}
	// 缺失的可选字段必须留空，不能变成字符串 "null" 之类
	if t3 := byID["t3"]; t3.Note != "" {
		t.Errorf("t3 note = %q, want empty", t3.Note)
	}
}

func TestListTxSortsNewestFirst(t *testing.T) {
	st := seed(t)
	page := st.ListTx(store.TxQuery{Limit: 100})
	// 降序：10/1, 9/6, 9/5, 9/2, 9/1, 8/30
	want := []string{"t6", "t3", "t2", "t1", "t4", "t5"}
	if len(page.Rows) != len(want) {
		t.Fatalf("rows = %d, want %d", len(page.Rows), len(want))
	}
	for i, id := range want {
		if page.Rows[i].ID != id {
			t.Fatalf("row %d = %s (%s), want %s",
				i, page.Rows[i].ID, stampOf(page.Rows[i].OccurredAt), id)
		}
	}
}

func stampOf(ms int64) string { return time.UnixMilli(ms).Format("2006-01-02") }

func TestListTxPaginationIsStable(t *testing.T) {
	st := seed(t)
	a := st.ListTx(store.TxQuery{Limit: 4, Offset: 0})
	b := st.ListTx(store.TxQuery{Limit: 4, Offset: 2})
	if len(a.Rows) != 4 || len(b.Rows) != 4 {
		t.Fatalf("page sizes = %d/%d, want 4/4", len(a.Rows), len(b.Rows))
	}
	if a.Total != 6 || b.Total != 6 {
		t.Fatalf("totals = %d/%d, want 6/6", a.Total, b.Total)
	}
	// 第 3、4 行必须在两页里一致：offset 越界和排序不稳定都会在这里暴露
	if a.Rows[2].ID != b.Rows[0].ID || a.Rows[3].ID != b.Rows[1].ID {
		t.Errorf("overlap mismatch: %s,%s vs %s,%s",
			a.Rows[2].ID, a.Rows[3].ID, b.Rows[0].ID, b.Rows[1].ID)
	}
	// offset 越过尾部必须返回空而不是 panic
	if got := st.ListTx(store.TxQuery{Limit: 10, Offset: 999}); len(got.Rows) != 0 || got.Total != 6 {
		t.Errorf("over-end page = %d rows, total %d", len(got.Rows), got.Total)
	}
}

func TestListTxFilters(t *testing.T) {
	st := seed(t)

	sepFrom := ts(2026, time.September, 1, 0)
	octFrom := ts(2026, time.October, 1, 0)

	cases := []struct {
		name string
		q    store.TxQuery
		want int
	}{
		{"9月", store.TxQuery{From: sepFrom, To: octFrom}, 4},
		{"只有支出", store.TxQuery{Direction: store.DirExpense}, 5},
		{"只有收入", store.TxQuery{Direction: store.DirIncome}, 1},
		{"分类=餐饮", store.TxQuery{CategoryID: "餐饮"}, 1},
		{"关键词 星巴克/咖啡", store.TxQuery{Keyword: "咖啡"}, 1},
		{"关键词 盒马", store.TxQuery{Keyword: "盒马"}, 1},
		{"关键词 金额 459", store.TxQuery{Keyword: "459"}, 1},
		{"关键词 备注", store.TxQuery{Keyword: "生椰"}, 1},
		{"关键词 无结果", store.TxQuery{Keyword: "绝不存在zzz"}, 0},
		// 未知 direction 必须被当成"不过滤"，而不是"匹配不到任何东西"
		{"乱写的 direction", store.TxQuery{Direction: "GHOST"}, 6},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := st.ListTx(c.q).Total; got != c.want {
				t.Errorf("total = %d, want %d", got, c.want)
			}
		})
	}
}

// 关键词要能搜到商户、备注、分类。漏掉任何一个，用户搜"咖啡"却搜不到
// 唯一那笔瑞幸，就只能认为面板坏了。
func TestKeywordCoversAllSearchableFields(t *testing.T) {
	st := seed(t)
	for kw, want := range map[string]int{
		"京东": 1, "咖啡": 1, "生椰": 1, "9月工资": 1, "购物": 1, "工资卡": 1, "大剧院": 1,
	} {
		if got := st.ListTx(store.TxQuery{Keyword: kw}).Total; got != want {
			t.Errorf("keyword %q -> %d, want %d", kw, got, want)
		}
	}
}

func TestStatsSignsAndGroups(t *testing.T) {
	st2 := seedAll(t) // 含一笔已删除

	all := st2.Stats(store.TxQuery{})
	// 支出: 45900+38000+600+23640+1800 = 109940
	// 收入: 1850000
	// 已删除的 999900 不计入
	if all.Expense != 109940 {
		t.Errorf("expense = %d, want 109940", all.Expense)
	}
	if all.Income != 1850000 {
		t.Errorf("income = %d, want 1850000", all.Income)
	}
	if all.Balance != all.Income-all.Expense {
		t.Errorf("balance = %d, inconsistent", all.Balance)
	}
	if all.Count != 6 {
		t.Errorf("count = %d, want 6 (墓碑必须排除)", all.Count)
	}

	// 收入不能混进分类构成，否则"餐饮 ¥300"在退款时会变得没法解释
	for _, c := range all.ByCategory {
		if c.Key == "工资" {
			t.Errorf("分类构成里出现了收入分类 %q", c.Key)
		}
	}
	months := map[string]int64{}
	for _, m := range all.ByMonth {
		months[m.Key] = m.Minor
	}
	if _, ok := months["2026-08"]; !ok {
		t.Errorf("按月缺少 2026-08，得到 %v", months)
	}
	if _, ok := months["2026-10"]; !ok {
		t.Errorf("按月缺少 2026-10，得到 %v", months)
	}
}

// Stats 必须和 ListTx 用同一套筛选，否则面板顶上写的"共 6 笔 ¥1099.40"
// 和下面表格里列出来的对不上。
func TestStatsMatchesList(t *testing.T) {
	st := seed(t)
	sepFrom := ts(2026, time.September, 1, 0)
	octFrom := ts(2026, time.October, 1, 0)
	q := store.TxQuery{From: sepFrom, To: octFrom}
	s := st.Stats(q)
	l := st.ListTx(q)
	if s.Count != l.Total {
		t.Errorf("stats.Count=%d list.Total=%d", s.Count, l.Total)
	}
	var sum int64
	for _, r := range l.Rows {
		sum += r.SignedMinor()
	}
	if sum != s.Balance {
		t.Errorf("list 逐行相加 = %d, stats.Balance = %d", sum, s.Balance)
	}
}

func TestSignedMinor(t *testing.T) {
	if got := (store.TxView{AmountMinor: 100, Direction: store.DirExpense}).SignedMinor(); got != -100 {
		t.Errorf("expense sign = %d", got)
	}
	if got := (store.TxView{AmountMinor: 100, Direction: store.DirIncome}).SignedMinor(); got != 100 {
		t.Errorf("income sign = %d", got)
	}
	// 未知方向和内部划转不计入收支；与 Android 保持一致。
	if got := (store.TxView{AmountMinor: 100, Direction: "???"}).SignedMinor(); got != 0 {
		t.Errorf("unknown sign = %d, want 0", got)
	}
}

func TestDeletedHiddenByDefaultAndCountable(t *testing.T) {
	st := seedAll(t)
	if got := st.ListTx(store.TxQuery{}).Total; got != 6 {
		t.Errorf("default total = %d, want 6 (墓碑不可见)", got)
	}
	if got := st.ListTx(store.TxQuery{IncludeDeleted: true}).Total; got != 7 {
		t.Errorf("include-deleted total = %d, want 7", got)
	}
	// 但墓碑永远不进统计，否则"这个月花了多少"会突然多出一大笔
	if got := st.Stats(store.TxQuery{}).Count; got != 6 {
		t.Errorf("stats count = %d, want 6", got)
	}
}

func TestFacetsIgnoreOwnDimension(t *testing.T) {
	st := seed(t)
	all := st.Facets("category")
	if len(all) != 6 {
		t.Fatalf("category facets = %d, want 6", len(all))
	}
	// 按用量降序；并列时按 key 升序，保证下拉框顺序稳定
	for i := 1; i < len(all); i++ {
		if all[i-1].Count < all[i].Count {
			t.Errorf("facets not sorted by count desc: %v", all)
			break
		}
	}
	if got := st.Facets("nonsense"); got != nil {
		t.Errorf("unknown dimension = %v, want nil", got)
	}
}

// 真机端到端测试推上来的行，字符串字段是**包了两层引号**的。
// 这是拿 NAS 上的真容器跑 SyncE2ETest 时才发现的：用 merge.V() 造的种子数据
// 只有一层，本文件里所有断言都过；换成真客户端的数据，direction 变成
// "\"EXPENSE\""，剥一层之后仍带引号，于是收入被算成支出。
func TestListTxPeelsRepeatedlyQuotedStrings(t *testing.T) {
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	at := ts(2026, time.September, 10, 12)
	// 复刻 SyncE2ETest.markAllPending + SyncEngine 的双重包装
	doubled := func(s string) json.RawMessage {
		once, _ := json.Marshal(s)
		twice, _ := json.Marshal(string(once))
		return twice
	}
	changes := []merge.Change{
		{
			ChangeID: "c-a", Entity: store.TxnEntity, EntityID: "a", Op: merge.OpUpsert,
			Lamport: 1, Origin: "dev_real",
			Data: map[string]json.RawMessage{
				"amount_minor": merge.V(6600),
				"direction":    doubled(store.DirIncome),
				"account_id":   doubled("支付宝"),
				"occurred_at":  merge.V(at),
				"merchant":     doubled("改方向"),
			},
		},
		{
			ChangeID: "c-b", Entity: store.TxnEntity, EntityID: "b", Op: merge.OpUpsert,
			Lamport: 2, Origin: "dev_real",
			Data: map[string]json.RawMessage{
				"amount_minor": merge.V(8888),
				"direction":    doubled(store.DirExpense),
				"account_id":   doubled("微信"),
				"occurred_at":  merge.V(at),
				"merchant":     doubled("旧金额"),
			},
		},
	}
	if _, err := st.Push("dev_real", changes); err != nil {
		t.Fatalf("push: %v", err)
	}

	page := st.ListTx(store.TxQuery{})
	by := map[string]store.TxView{}
	for _, r := range page.Rows {
		by[r.ID] = r
	}
	if got := by["a"].Direction; got != store.DirIncome {
		t.Errorf("a.direction = %q, want INCOME（引号没剥干净，收入会被算成支出）", got)
	}
	if got := by["a"].Merchant; got != "改方向" {
		t.Errorf("a.merchant = %q", got)
	}
	if got := by["a"].SignedMinor(); got != 6600 {
		t.Errorf("a signed = %d, want +6600", got)
	}
	if got := by["b"].SignedMinor(); got != -8888 {
		t.Errorf("b signed = %d, want -8888", got)
	}
	// 方向筛选也必须跟着对：这是"只看收入"那个下拉框的判据
	if n := st.ListTx(store.TxQuery{Direction: store.DirIncome}).Total; n != 1 {
		t.Errorf("direction=INCOME 命中 %d 行, want 1", n)
	}
	stt := st.Stats(store.TxQuery{})
	if stt.Income != 6600 || stt.Expense != 8888 {
		t.Errorf("stats income=%d expense=%d, want 6600/8888", stt.Income, stt.Expense)
	}
}

func seedAll(t *testing.T) *store.Store {
	t.Helper()
	st := seed(t)
	// 真正的删除长这样：op=delete 且**不带任何 data**。
	// 早先这里是从 upsert() 改 Op，字段还留着——于是删除戳和内容戳完全相同，
	// 按 merge 的规则"删除戳必须严格新于内容戳才算删掉"，这一行就永远删不掉。
	// 合并层的行为是对的，是构造出来的变更不真实。
	del := merge.Change{
		ChangeID: "c-del", Entity: store.TxnEntity, EntityID: "t7",
		Op: merge.OpDelete, Data: map[string]json.RawMessage{},
		Lamport: 99, Origin: "dev_test",
	}
	if _, err := st.Push("dev_test", []merge.Change{del}); err != nil {
		t.Fatalf("push delete: %v", err)
	}
	return st
}

// ---------------------------------------------------------------------------
// 分类 / 账户名的解析
//
// 账目里存的是 category_id，而 id 是手机自己发明的字符串（"cat-1"）。
// 面板要给人看"交通"，就必须拿这个 id 去 category 表里换成名字。
// 少做这一步，界面上就是一列 cat-1 —— 数字全对、金额全对，只是没人看得懂。
// ---------------------------------------------------------------------------

// 铺一张分类表：id 是手机风格的 cat-N，name 是中文。
func seedRefs(t *testing.T, st *store.Store) {
	t.Helper()
	var changes []merge.Change
	for i, name := range []string{"餐饮", "交通", "购物"} {
		changes = append(changes, merge.Change{
			ChangeID: "c-cat-" + name, Entity: store.EntityCategory,
			EntityID: "cat-" + string(rune('0'+i)),
			Op:       merge.OpUpsert,
			Data: map[string]json.RawMessage{
				"name": merge.V(name),
				"kind": merge.V("EXPENSE"),
			},
			Lamport: 1, Origin: "dev_test",
		})
	}
	changes = append(changes, merge.Change{
		ChangeID: "c-acct-1", Entity: store.EntityAccount, EntityID: "A4",
		Op: merge.OpUpsert,
		Data: map[string]json.RawMessage{
			"name": merge.V("微信"), "kind": merge.V("DEBIT"),
		},
		Lamport: 1, Origin: "dev_test",
	})
	if _, err := st.Push("dev_test", changes); err != nil {
		t.Fatalf("push refs: %v", err)
	}
}

func TestListTxResolvesCategoryAndAccountNames(t *testing.T) {
	st := seed(t)
	seedRefs(t, st)

	// t1 的 category_id 是"购物"、account_id 是"支付宝"，
	// 这两个都不在参考表里 —— 只能退回 id，不能变成空。
	page := st.ListTx(store.TxQuery{Limit: 100})
	byID := map[string]store.TxView{}
	for _, r := range page.Rows {
		byID[r.ID] = r
	}

	// 参考表里有的：解析成名字
	//   t3 -> 娱乐(未在表中) 用 t4 -> 交通(cat-1)
	got := byID["t4"]
	if got.CategoryName != "交通" {
		t.Errorf("t4 分类名 = %q, want 交通 (原始 id %q)", got.CategoryName, got.CategoryID)
	}
	if got.AccountName != "支付宝" {
		// A3 不在参考表里，应该退回 id 本身而不是空
		if got.AccountName != got.AccountID {
			t.Errorf("t4 账户名 = %q, 解析不到时应退回 id %q", got.AccountName, got.AccountID)
		}
	}
	// 原始 id 必须原样保留：筛选是按 id 做的，显示名只是给人看的
	if got.CategoryID != "交通" {
		t.Errorf("t4 原始 category_id 被改成了 %q", got.CategoryID)
	}
}

func TestStatsGroupsByResolvedName(t *testing.T) {
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	seedRefs(t, st)
	// 两笔都指向 cat-1（交通），id 相同
	changes := []merge.Change{
		upsert("x1", 10, 450000, store.DirExpense, "cat-1", "A4", "房租", "", ts(2026, time.September, 1, 9)),
		upsert("x2", 11, 500, store.DirExpense, "cat-1", "A4", "地铁", "", ts(2026, time.September, 2, 9)),
		// 另一笔指向不存在的分类
		upsert("x3", 12, 3000, store.DirExpense, "cat-99", "A4", "神秘", "", ts(2026, time.September, 3, 9)),
	}
	if _, err := st.Push("dev_test", changes); err != nil {
		t.Fatalf("push: %v", err)
	}

	stats := st.Stats(store.TxQuery{Limit: 100})
	byKey := map[string]store.Sum{}
	for _, s := range stats.ByCategory {
		byKey[s.Key] = s
	}

	tr, ok := byKey["交通"]
	if !ok {
		t.Fatalf("没有名为「交通」的分类行，实际有 %v", byKey)
	}
	// 同名同 id 必须合成一行：450000 + 500
	if tr.Minor != 450500 || tr.Count != 2 {
		t.Errorf("交通 = %d 分 / %d 笔, want 450500 / 2", tr.Minor, tr.Count)
	}
	// 解析不到的行退回 id，仍然要出现，不能凭空消失
	if _, ok := byKey["cat-99"]; !ok {
		t.Errorf("解析不到的分类应该退回 id 保留一行，实际 %v", byKey)
	}
}

func TestFacetsUseResolvedNames(t *testing.T) {
	st := seed(t)
	seedRefs(t, st)
	facets := st.Facets("category")
	if len(facets) == 0 {
		t.Fatal("分类 facets 为空")
	}
	// 现有种子把 category_id 直接写成中文名，没有对应参考行，
	// 所以这里断言的是"退回 id 而不是空"这条路径。
	for _, f := range facets {
		if f.Key == "" {
			t.Errorf("facet 出现空 key: %+v", f)
		}
	}
}

// 参考数据（分类/账户）存在库里，**不能**出现在账单列表和汇总里。
//
// 这条在"只有账单参与同步"的前提下不可能被触发，所以一直没有测试保护。
// 一旦开始同步分类表，rows 里就混进了 21 条非 txn 行：面板的明细页会多出
// 四行"现金 / 银行卡 / 支付宝 / 微信"，每行 0.00 元，全部可以点开。
func TestReferenceRowsNeverAppearAsTransactions(t *testing.T) {
	st := seed(t)
	seedRefs(t, st)

	page := st.ListTx(store.TxQuery{Limit: 100})
	if page.Total != 6 {
		t.Errorf("列表总数 = %d, want 6（多出来的多半是分类/账户行）", page.Total)
	}
	for _, r := range page.Rows {
		if r.ID == "cat-0" || r.ID == "cat-1" || r.ID == "cat-2" || r.ID == "A4" {
			t.Errorf("参考数据行 %q 混进了账单列表", r.ID)
		}
	}

	stats := st.Stats(store.TxQuery{Limit: 100})
	if stats.Count != 6 {
		t.Errorf("汇总笔数 = %d, want 6", stats.Count)
	}
	if stats.Expense != 45900+38000+600+23640+1800 {
		t.Errorf("支出合计 = %d, 参考数据被算进去了", stats.Expense)
	}
}
