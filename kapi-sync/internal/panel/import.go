package panel

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"kapi/sync/internal/billimport"
	"kapi/sync/internal/capture"
	"kapi/sync/internal/store"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

type importRunner interface {
	Run(context.Context, billimport.Request) (*billimport.Result, error)
	Available() bool
}
type importTicket struct {
	Preview importPreview
	Expires time.Time
	File    string
	Edits   []billimport.Edit
	Mapping map[string]int
	Saved   *store.BillImportSaved
}
type importPreview struct {
	Ticket     string                 `json:"ticket"`
	Revision   int64                  `json:"revision"`
	LedgerID   string                 `json:"ledgerId"`
	Parsed     *billimport.Result     `json:"parsed"`
	Candidates []billimport.Candidate `json:"candidates"`
	Catalog    store.ShortcutCatalog  `json:"catalog"`
}
type importService struct {
	st      *store.Store
	worker  importRunner
	mu      sync.Mutex
	tickets map[string]*importTicket
}

func newImportService(st *store.Store) *importService {
	return &importService{st: st, worker: billimport.NewWorker(), tickets: map[string]*importTicket{}}
}
func (h *Handler) importCatalog(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(405)
		return
	}
	_, _, catalog := h.st.BillImportSnapshot(r.URL.Query().Get("ledger_id"))
	writeJSON(w, 200, map[string]any{"available": h.imports.worker.Available(), "catalog": catalog})
}
func (h *Handler) importWriteAllowed(w http.ResponseWriter, r *http.Request) bool {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		w.WriteHeader(405)
		return false
	}
	if !constantTimeEqual(r.Header.Get("X-Panel-Token"), h.token) {
		writeJSON(w, 401, map[string]string{"message": "请重新登录面板"})
		return false
	}
	if origin := r.Header.Get("Origin"); origin != "" {
		u, err := url.Parse(origin)
		if err != nil || u.Host != r.Host || u.Scheme != "http" && u.Scheme != "https" {
			writeJSON(w, 403, map[string]string{"message": "请从 NAS 面板提交导入"})
			return false
		}
	}
	return true
}
func (h *Handler) importPreview(w http.ResponseWriter, r *http.Request) {
	if !h.importWriteAllowed(w, r) {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 20*1024*1024+65536)
	if err := r.ParseMultipartForm(21 * 1024 * 1024); err != nil {
		writeJSON(w, 400, map[string]string{"message": "请选择 20 MB 以内的账单文件"})
		return
	}
	if r.MultipartForm != nil {
		defer r.MultipartForm.RemoveAll()
	}
	f, _, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, map[string]string{"message": "请先选择账单文件"})
		return
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, 20*1024*1024+1))
	if err != nil || len(data) > 20*1024*1024 {
		writeJSON(w, 400, map[string]string{"message": "文件超过 20 MB，请分批导入"})
		return
	}
	ledger := r.FormValue("ledger_id")
	svc := h.imports
	parsed, err := svc.worker.Run(r.Context(), billimport.Request{Mode: "parse", File: base64.StdEncoding.EncodeToString(data)})
	if err != nil {
		writeJSON(w, 400, map[string]string{"message": err.Error()})
		return
	}
	persistedMapping := h.st.ImportFormat(ledger, parsed.FormatKey)
	if saved := persistedMapping; len(saved) > 0 {
		parsed, err = svc.worker.Run(r.Context(), billimport.Request{Mode: "field-apply", File: base64.StdEncoding.EncodeToString(data), Mapping: saved})
		if err != nil {
			writeJSON(w, 400, map[string]string{"message": "保存的字段匹配不再适用，请重新核对文件格式"})
			return
		}
	}
	_, candidates, catalog, err := svc.match(r.Context(), ledger, parsed.Rows)
	if err != nil {
		writeJSON(w, 400, map[string]string{"message": err.Error()})
		return
	}
	valid := false
	for _, l := range catalog.Ledgers {
		if l.ID == ledger {
			valid = true
		}
	}
	if !valid {
		writeJSON(w, 400, map[string]string{"message": "请先选择已从手机同步的账本"})
		return
	}
	parsed.Rows = nil
	preview := importPreview{Ticket: randomToken(), Revision: 1, LedgerID: ledger, Parsed: parsed, Candidates: candidates, Catalog: catalog}
	svc.mu.Lock()
	defer svc.mu.Unlock()
	for id, t := range svc.tickets {
		if time.Now().After(t.Expires) {
			delete(svc.tickets, id)
		}
	}
	active := 0
	for _, t := range svc.tickets {
		if t.Saved == nil {
			active++
		}
	}
	if len(svc.tickets) >= 64 {
		var oldest string
		var expiry time.Time
		for id, t := range svc.tickets {
			if t.Saved != nil && (oldest == "" || t.Expires.Before(expiry)) {
				oldest = id
				expiry = t.Expires
			}
		}
		if oldest != "" {
			delete(svc.tickets, oldest)
		}
	}
	if active >= 8 {
		writeJSON(w, 429, map[string]string{"message": "待核对文件过多，请完成导入或 20 分钟后再试"})
		return
	}
	svc.tickets[preview.Ticket] = &importTicket{Preview: preview, File: base64.StdEncoding.EncodeToString(data), Mapping: persistedMapping, Expires: time.Now().Add(20 * time.Minute)}
	writeJSON(w, 200, preview)
}

func (h *Handler) importCancel(w http.ResponseWriter, r *http.Request) {
	if !h.importWriteAllowed(w, r) {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	var body struct {
		Ticket string `json:"ticket"`
	}
	if json.NewDecoder(r.Body).Decode(&body) != nil {
		w.WriteHeader(400)
		return
	}
	svc := h.imports
	svc.mu.Lock()
	defer svc.mu.Unlock()
	if t := svc.tickets[body.Ticket]; t != nil && t.Saved == nil {
		delete(svc.tickets, body.Ticket)
	}
	writeJSON(w, 200, map[string]bool{"cancelled": true})
}
func (s *importService) match(ctx context.Context, ledger string, rows []billimport.Row) (int64, []billimport.Candidate, store.ShortcutCatalog, error) {
	seq, existing, catalog := s.st.BillImportSnapshot(ledger)
	result, err := s.worker.Run(ctx, billimport.Request{Mode: "match", LedgerID: ledger, Rows: rows, Existing: existing})
	if err != nil {
		return seq, nil, catalog, err
	}
	name := func(options []store.ShortcutOption, text, kind string) string {
		if text == "" {
			return ""
		}
		ids := []string{}
		for _, a := range options {
			if (a.Path == text || a.Name == text) && (kind == "" || a.Kind == kind) {
				ids = append(ids, a.ID)
			}
		}
		if len(ids) == 1 {
			return ids[0]
		}
		return ""
	}
	text := func(s *string) string {
		if s == nil {
			return ""
		}
		return *s
	}
	mappings := s.st.ImportCategoryMappings(ledger)
	for i, c := range result.Candidates {
		a := name(catalog.Accounts, text(c.Row.Account), "")
		if a == "" && c.Row.Source == "BILL_WECHAT" && contains([]string{"零钱", "微信支付", "微信"}, text(c.Row.Account)) {
			a = name(catalog.Accounts, "微信", "")
		}
		if a == "" && c.Row.Source == "BILL_ALIPAY" && contains([]string{"余额", "支付宝余额", "支付宝"}, text(c.Row.Account)) {
			a = name(catalog.Accounts, "支付宝", "")
		}
		result.Candidates[i].AccountID = a
		if a == "" {
			result.Candidates[i].AccountHints = bankAccountHints(text(c.Row.Account), catalog.Accounts)
		}
		result.Candidates[i].ToAccountID = name(catalog.Accounts, text(c.Row.ToAccount), "")
		cat := name(catalog.Categories, text(c.Row.Category), c.Row.Direction)
		if cat == "" && (c.Row.Category == nil || c.Row.Source == "BILL_ALIPAY" || c.Row.Source == "BILL_WECHAT") {
			suggestion := s.st.ShortcutCategory(capture.Draft{Direction: c.Row.Direction, Merchant: text(c.Row.Merchant), Product: text(c.Row.Note)}, ledger)
			for _, o := range catalog.Categories {
				if o.ID == suggestion {
					cat = suggestion
				}
			}
		}
		if learned := mappings[store.ImportCategoryKey(c.Row)]; learned != "" && c.Row.ToAccount == nil {
			for _, o := range catalog.Categories {
				if o.ID == learned && o.Kind == c.Row.Direction {
					cat = learned
				}
			}
		}
		result.Candidates[i].CategoryID = cat
	}
	return seq, result.Candidates, catalog, nil
}
func contains(options []string, s string) bool {
	for _, v := range options {
		if v == s {
			return true
		}
	}
	return false
}
func (h *Handler) importCommit(w http.ResponseWriter, r *http.Request) {
	if !h.importWriteAllowed(w, r) {
		return
	}
	if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		writeJSON(w, 400, map[string]string{"message": "导入请求格式无效"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2*1024*1024)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	var options store.BillImportCommit
	if err := dec.Decode(&options); err != nil {
		writeJSON(w, 400, map[string]string{"message": "导入选择无效"})
		return
	}
	var extra any
	if dec.Decode(&extra) != io.EOF {
		writeJSON(w, 400, map[string]string{"message": "导入请求包含多余数据"})
		return
	}
	svc := h.imports
	svc.mu.Lock()
	defer svc.mu.Unlock()
	ticket := svc.tickets[options.Ticket]
	if ticket == nil || time.Now().After(ticket.Expires) {
		writeJSON(w, 410, map[string]string{"message": "预览已过期，请重新上传核对"})
		return
	}
	if ticket.Saved != nil {
		writeJSON(w, 200, ticket.Saved)
		return
	}
	p := ticket.Preview
	if options.Revision != p.Revision {
		writeJSON(w, 409, map[string]string{"message": "预览已修正，请重新核对当前所选账单"})
		return
	}
	if p.Parsed.IssueCount > 0 && !options.AcknowledgeIssues {
		writeJSON(w, 400, map[string]string{"message": "请先确认已核对不会导入的内容"})
		return
	}
	if options.PreserveCategories && (p.Parsed.Source == "BILL_WECHAT" || p.Parsed.Source == "BILL_ALIPAY" || p.Parsed.Source == "BILL_BANK_ICBC" || p.Parsed.Source == "BILL_BANK_TABLE") {
		writeJSON(w, 400, map[string]string{"message": "支付账单不能直接建立原分类，请选择未分类后在手机调整"})
		return
	}
	rows := make([]billimport.Row, len(p.Candidates))
	for i, c := range p.Candidates {
		rows[i] = c.Row
	}
	seq, fresh, _, err := svc.match(r.Context(), p.LedgerID, rows)
	if err != nil {
		writeJSON(w, 400, map[string]string{"message": err.Error()})
		return
	}
	options.ActorDeviceID = r.Header.Get("X-Guizhang-Family-Actor")
	result, err := h.st.CommitBillImport(seq, p.LedgerID, p.Candidates, fresh, options)
	if err != nil {
		status := 400
		if errors.Is(err, store.ErrImportChanged) {
			status = 409
		}
		writeJSON(w, status, map[string]string{"message": err.Error()})
		return
	}
	if result.Added > 0 && options.RememberMappings {
		if e := h.st.RememberImportFormat(p.LedgerID, p.Parsed.FormatKey, p.Parsed.Mapping); e != nil {
			result.MappingWarning += "账单已保存，但字段匹配未记住"
		}
	}
	ticket.File = ""
	ticket.Edits = nil
	ticket.Saved = result
	writeJSON(w, 200, result)
}

func (h *Handler) importRepairRow(w http.ResponseWriter, r *http.Request) { h.importEdit(w, r, false) }
func (h *Handler) importRepair(w http.ResponseWriter, r *http.Request)    { h.importEdit(w, r, true) }
func (h *Handler) importEdit(w http.ResponseWriter, r *http.Request, apply bool) {
	if !h.importWriteAllowed(w, r) {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 16384)
	var body struct {
		Ticket   string            `json:"ticket"`
		Revision int64             `json:"revision"`
		Row      int               `json:"row"`
		Changes  map[string]string `json:"changes"`
	}
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if dec.Decode(&body) != nil || dec.Decode(&struct{}{}) != io.EOF {
		writeJSON(w, 400, map[string]string{"message": "修正内容无效"})
		return
	}
	svc := h.imports
	svc.mu.Lock()
	defer svc.mu.Unlock()
	t := svc.tickets[body.Ticket]
	if t == nil || time.Now().After(t.Expires) {
		writeJSON(w, 410, map[string]string{"message": "预览已过期，请重新上传核对"})
		return
	}
	if t.Saved != nil || body.Revision != t.Preview.Revision {
		writeJSON(w, 409, map[string]string{"message": "预览已变化或已经导入，请重新核对"})
		return
	}
	allowed := false
	for _, issue := range t.Preview.Parsed.Issues {
		if issue.Row == body.Row && body.Row > 0 {
			allowed = true
		}
	}
	if !allowed {
		writeJSON(w, 400, map[string]string{"message": "只能修正当前错误行"})
		return
	}
	edits := append([]billimport.Edit{}, t.Edits...)
	mode := "fields"
	if apply {
		if len(body.Changes) == 0 {
			writeJSON(w, 400, map[string]string{"message": "请填写要修正的内容"})
			return
		}
		for field, value := range body.Changes {
			if !contains([]string{"TIME", "CLOCK", "AMOUNT", "DIRECTION", "INCOME", "EXPENSE", "CURRENCY", "NATURE", "ACCOUNT", "TO_ACCOUNT"}, field) || len([]rune(value)) > 128 {
				writeJSON(w, 400, map[string]string{"message": "仅可修正时间、金额、收支、币种、交易性质和账户"})
				return
			}
		}
		merged := map[string]string{}
		at := -1
		for i, e := range edits {
			if e.Row == body.Row {
				at = i
				for k, v := range e.Changes {
					merged[k] = v
				}
			}
		}
		for k, v := range body.Changes {
			merged[k] = v
		}
		edit := billimport.Edit{Row: body.Row, Changes: merged}
		if at >= 0 {
			edits[at] = edit
		} else {
			edits = append(edits, edit)
		}
		mode = "repair"
	}
	parsed, err := svc.worker.Run(r.Context(), billimport.Request{Mode: mode, File: t.File, Edits: edits, RepairRow: body.Row, Mapping: editableFormatMapping(t.Mapping)})
	if err != nil {
		writeJSON(w, 400, map[string]string{"message": err.Error()})
		return
	}
	if !apply {
		writeJSON(w, 200, map[string]any{"row": body.Row, "values": parsed.RepairValues})
		return
	}
	_, candidates, catalog, err := svc.match(r.Context(), t.Preview.LedgerID, parsed.Rows)
	if err != nil {
		writeJSON(w, 400, map[string]string{"message": err.Error()})
		return
	}
	parsed.Rows = nil
	t.Edits = edits
	t.Preview.Parsed = parsed
	t.Preview.Candidates = candidates
	t.Preview.Catalog = catalog
	t.Preview.Revision++
	writeJSON(w, 200, t.Preview)
}

// Suggestions only: never bind accounts from a fuzzy bank name or card tail.
var accountTailPattern = regexp.MustCompile(`(?:尾号[：: ]*|[（(])([0-9]{4})[）)]?`)

func bankAccountHints(raw string, options []store.ShortcutOption) []string {
	if raw == "" {
		return nil
	}
	tail := ""
	bank := ""
	re := accountTailPattern
	if m := re.FindStringSubmatch(raw); len(m) > 1 {
		tail = m[1]
	}
	for _, name := range []string{"工商", "建设", "农业", "中国银行", "招商", "交通", "邮储", "平安", "兴业", "中信", "浦发", "民生", "光大"} {
		if strings.Contains(raw, name) {
			bank = name
			break
		}
	}
	var out []string
	for _, o := range options {
		if o.Name == raw || tail != "" && strings.Contains(o.Name, tail) && (bank == "" || strings.Contains(o.Name, bank)) || tail == "" && bank != "" && strings.Contains(o.Name, bank) {
			out = append(out, o.Name)
			if len(out) == 3 {
				break
			}
		}
	}
	return out
}

func editableFormatMapping(m map[string]int) map[string]int {
	out := map[string]int{}
	for f, i := range m {
		if f != "NATURE" && f != "TIME_EVIDENCE" {
			out[f] = i
		}
	}
	return out
}
