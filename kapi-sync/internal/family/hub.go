// Package family keeps private and shared journals in separate sync spaces.
// Existing root data stays private to the owner. Common local IDs never cross
// members, and family membership is enforced before forwarding any request.
package family

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/panel"
	"kapi/sync/internal/quotes"
	"kapi/sync/internal/store"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Member struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Active bool   `json:"active"`
}
type invitation struct {
	Hash      string `json:"hash"`
	MemberID  string `json:"member_id"`
	Name      string `json:"name"`
	ExpiresAt int64  `json:"expires_at"`
}
type state struct {
	Version       int                   `json:"version"`
	Enabled       bool                  `json:"enabled"`
	SharedID      string                `json:"shared_id"`
	Members       map[string]Member     `json:"members"`
	Invitations   map[string]invitation `json:"invitations"`
	SharedDevices map[string]string     `json:"shared_devices"`
}
type space struct {
	st         *store.Store
	handler    http.Handler
	panelToken string
	panel      *panel.Handler
}
type Factory func(*store.Store) http.Handler

type Hub struct {
	mu              sync.RWMutex
	root            *store.Store
	dir, adminToken string
	factory         Factory
	state           state
	private         map[string]*space
	shared          *space
	now             func() time.Time
	policy          atomic.Value
	quotes          *quotes.Service
}
type Connection struct {
	SpaceID  string `json:"space_id"`
	Kind     string `json:"kind"`
	Name     string `json:"name"`
	BasePath string `json:"base_path"`
	DeviceID string `json:"device_id"`
	Token    string `json:"token"`
	Cursor   int64  `json:"cursor"`
}
type Session struct {
	Member   Member       `json:"member"`
	Personal Connection   `json:"personal"`
	Shared   []Connection `json:"shared"`
	EditAll  bool         `json:"allow_member_edits"`
}

func Open(root *store.Store, dataDir, adminToken string, factory Factory) (*Hub, error) {
	h := &Hub{root: root, dir: filepath.Join(dataDir, "family"), adminToken: adminToken, factory: factory, private: map[string]*space{}, now: time.Now}
	h.state = state{Version: 1, Members: map[string]Member{"owner": {ID: "owner", Name: "成员 1", Active: true}}, Invitations: map[string]invitation{}, SharedDevices: map[string]string{}}
	b, err := os.ReadFile(filepath.Join(h.dir, "family.json"))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if err == nil {
		if err = json.Unmarshal(b, &h.state); err != nil {
			return nil, err
		}
		if h.state.Version != 1 || h.state.Members == nil || h.state.Invitations == nil || h.state.SharedDevices == nil {
			return nil, errors.New("invalid family registry")
		}
	}
	// Rename only the former generated label, never a member's custom nickname.
	if member, ok := h.state.Members["owner"]; ok && member.Name == "家庭管理员" {
		member.Name = "成员 1"
		h.state.Members["owner"] = member
	}
	h.publishPolicyLocked()
	if h.state.Enabled {
		if !validID(h.state.SharedID) {
			return nil, errors.New("invalid shared space ID")
		}
		if h.shared, err = h.openSpace(filepath.Join(h.dir, "shared", h.state.SharedID)); err != nil {
			return nil, err
		}
		for id, m := range h.state.Members {
			if id == "owner" {
				continue
			}
			if !validID(id) || m.ID != id {
				h.closeLocked()
				return nil, errors.New("invalid member ID")
			}
			s, e := h.openSpace(filepath.Join(h.dir, "private", id))
			if e != nil {
				h.closeLocked()
				return nil, e
			}
			h.private[id] = s
		}
	}
	return h, nil
}
func validID(id string) bool {
	if len(id) != 24 {
		return false
	}
	_, e := hex.DecodeString(id)
	return e == nil
}
func randomID() (string, error) {
	b := make([]byte, 12)
	_, e := rand.Read(b)
	return hex.EncodeToString(b), e
}
func hash(s string) string { v := sha256.Sum256([]byte(s)); return hex.EncodeToString(v[:]) }
func (h *Hub) openSpace(dir string) (*space, error) {
	s, e := store.Open(dir)
	if e != nil {
		return nil, e
	}
	ui, token := panel.New(s, "")
	if h.quotes != nil {
		ui.EnableQuotes(h.quotes)
	}
	if filepath.Base(filepath.Dir(dir)) == "shared" {
		s.SetWriteGuard(h.guardSharedWrite)
	}
	mux := http.NewServeMux()
	mux.Handle("/v1/", h.factory(s))
	ui.Register(mux)
	return &space{st: s, handler: mux, panelToken: token, panel: ui}, nil
}
func (h *Hub) Close() error { h.mu.Lock(); defer h.mu.Unlock(); return h.closeLocked() }
func (h *Hub) closeLocked() error {
	var result error
	if h.shared != nil {
		result = h.shared.st.Close()
		h.shared = nil
	}
	for id, s := range h.private {
		if e := s.st.Close(); result == nil {
			result = e
		}
		delete(h.private, id)
	}
	return result
}
func (h *Hub) saveLocked() error {
	if e := os.MkdirAll(h.dir, 0700); e != nil {
		return e
	}
	b, e := json.MarshalIndent(h.state, "", "  ")
	if e != nil {
		return e
	}
	f, e := os.OpenFile(filepath.Join(h.dir, "family.json.tmp"), os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if e != nil {
		return e
	}
	if _, e = f.Write(b); e == nil {
		e = f.Sync()
	}
	if v := f.Close(); e == nil {
		e = v
	}
	if e != nil {
		return e
	}
	if e = os.Rename(filepath.Join(h.dir, "family.json.tmp"), filepath.Join(h.dir, "family.json")); e != nil {
		return e
	}
	d, e := os.Open(h.dir)
	if e != nil {
		return e
	}
	defer d.Close()
	err := d.Sync()
	if err == nil {
		h.publishPolicyLocked()
	}
	return err
}
func bearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if strings.HasPrefix(h, "Bearer ") {
		return strings.TrimPrefix(h, "Bearer ")
	}
	return ""
}
func (h *Hub) owner(r *http.Request) bool {
	t := r.Header.Get("X-Panel-Token")
	return t != "" && h.adminToken != "" && subtle.ConstantTimeCompare([]byte(t), []byte(h.adminToken)) == 1
}
func reply(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func failure(w http.ResponseWriter, status int, message string) {
	reply(w, status, map[string]string{"error": "family_access", "message": message})
}
func body(w http.ResponseWriter, r *http.Request, to any) bool {
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10))
	d.DisallowUnknownFields()
	if e := d.Decode(to); e != nil {
		failure(w, 400, "请求格式不正确")
		return false
	}
	return true
}
func (h *Hub) Register(m *http.ServeMux) {
	m.HandleFunc("/v1/family/status", h.status)
	m.HandleFunc("/v1/family/enable", h.enable)
	m.HandleFunc("/v1/family/invite", h.invite)
	m.HandleFunc("/v1/family/join", h.join)
	m.HandleFunc("/v1/family/connections", h.connections)
	m.HandleFunc("/v1/family/revoke", h.revoke)
	m.HandleFunc("/family/space/", h.serveSpace)
}
func (h *Hub) status(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		failure(w, 405, "请使用 GET")
		return
	}
	if !h.owner(r) {
		failure(w, 403, "请在 NAS 连接维护面板查看成员")
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	members := make([]Member, 0, len(h.state.Members))
	for _, m := range h.state.Members {
		members = append(members, m)
	}
	sort.Slice(members, func(i, j int) bool { return members[i].ID < members[j].ID })
	reply(w, 200, map[string]any{"enabled": h.state.Enabled, "members": members, "shared_id": h.state.SharedID, "protocol": "family-spaces-v1", "allow_member_edits": false})
}
func (h *Hub) enable(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "请使用 POST")
		return
	}
	if !h.owner(r) {
		failure(w, 403, "请在 NAS 连接维护面板创建家庭")
		return
	}
	var config struct {
		EditAll bool `json:"allow_member_edits"`
	}
	if r.ContentLength != 0 && !body(w, r, &config) {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.state.Enabled {
		reply(w, 200, map[string]bool{"enabled": true})
		return
	}
	id, e := randomID()
	if e != nil {
		failure(w, 500, "创建家庭失败")
		return
	}
	s, e := h.openSpace(filepath.Join(h.dir, "shared", id))
	if e != nil {
		failure(w, 500, "创建家庭失败")
		return
	}
	if e = seedShared(s.st); e != nil {
		s.st.Close()
		failure(w, 500, "初始化家庭账本失败")
		return
	}
	h.state.Enabled = true
	// Accept the legacy request field, but never grant cross-recorder edits.
	h.state.SharedID = id
	h.shared = s
	if e = h.saveLocked(); e != nil {
		h.state.Enabled = false
		h.state.SharedID = ""
		h.shared = nil
		s.st.Close()
		failure(w, 500, "保存家庭失败")
		return
	}
	reply(w, 200, map[string]bool{"enabled": true})
}
func (h *Hub) invite(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "请使用 POST")
		return
	}
	if !h.owner(r) {
		failure(w, 403, "请在 NAS 连接维护面板生成邀请")
		return
	}
	var in struct {
		Name string `json:"name"`
	}
	if !body(w, r, &in) {
		return
	}
	in.Name = strings.TrimSpace(in.Name)
	if len([]rune(in.Name)) < 1 || len([]rune(in.Name)) > 24 {
		failure(w, 400, "成员昵称需为 1–24 字")
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.state.Enabled {
		failure(w, 409, "请先创建家庭账本")
		return
	}
	now := h.now().UnixMilli()
	for k, v := range h.state.Invitations {
		if v.ExpiresAt <= now {
			delete(h.state.Invitations, k)
		}
	}
	if len(h.state.Members)+len(h.state.Invitations) >= 20 {
		failure(w, 409, "家庭最多支持 20 位成员，请先处理未使用的邀请")
		return
	}
	code, e := randomID()
	if e != nil {
		failure(w, 500, "生成邀请失败")
		return
	}
	id, e := randomID()
	if e != nil {
		failure(w, 500, "生成邀请失败")
		return
	}
	key := hash(code)
	expiry := now + int64(15*time.Minute/time.Millisecond)
	h.state.Invitations[key] = invitation{key, id, in.Name, expiry}
	if e = h.saveLocked(); e != nil {
		delete(h.state.Invitations, key)
		failure(w, 500, "保存邀请失败")
		return
	}
	reply(w, 200, map[string]any{"code": code, "expires_at": expiry, "name": in.Name})
}
func (h *Hub) pairSpace(s *store.Store, name string) (*store.PairResult, error) {
	code, e := s.PairCode()
	if e != nil {
		return nil, e
	}
	return s.Pair(code, name)
}
func (h *Hub) issueSharedLocked(memberID, name string) (Connection, error) {
	p, e := h.pairSpace(h.shared.st, name)
	if e != nil {
		return Connection{}, e
	}
	h.state.SharedDevices[p.DeviceID] = memberID
	return Connection{h.state.SharedID, "SHARED", "家庭账本", "/family/space/shared/" + h.state.SharedID, p.DeviceID, p.Token, p.Cursor}, nil
}
func (h *Hub) join(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "请使用 POST")
		return
	}
	var in struct {
		Code       string `json:"code"`
		DeviceName string `json:"device_name"`
		Protocol   string `json:"protocol"`
	}
	if !body(w, r, &in) {
		return
	}
	if in.Protocol != "family-spaces-v1" {
		failure(w, 409, "请先更新归账，旧版本不能同时隔离个人与家庭账本")
		return
	}
	if len(in.Code) > 64 || len([]rune(in.DeviceName)) > 80 {
		failure(w, 400, "邀请信息不正确")
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	key := hash(strings.TrimSpace(in.Code))
	iv, ok := h.state.Invitations[key]
	if !h.state.Enabled || !ok || iv.ExpiresAt <= h.now().UnixMilli() {
		failure(w, 403, "邀请码已过期或已使用，请从 NAS 连接维护页重新生成")
		return
	}
	s, e := h.openSpace(filepath.Join(h.dir, "private", iv.MemberID))
	if e != nil {
		failure(w, 500, "创建个人空间失败")
		return
	}
	p, e := h.pairSpace(s.st, in.DeviceName)
	if e != nil {
		s.st.Close()
		failure(w, 500, "连接个人空间失败")
		return
	}
	shared, e := h.issueSharedLocked(iv.MemberID, in.DeviceName)
	if e != nil {
		s.st.Close()
		failure(w, 500, "连接家庭账本失败")
		return
	}
	member := Member{iv.MemberID, iv.Name, true}
	h.state.Members[member.ID] = member
	delete(h.state.Invitations, key)
	if e = h.saveLocked(); e != nil {
		delete(h.state.Members, member.ID)
		delete(h.state.SharedDevices, shared.DeviceID)
		h.state.Invitations[key] = iv
		s.st.Close()
		failure(w, 500, "保存成员失败，请重试")
		return
	}
	h.private[member.ID] = s
	reply(w, 200, Session{member, Connection{member.ID, "PERSONAL", "个人账本", "/family/space/private/" + member.ID, p.DeviceID, p.Token, p.Cursor}, []Connection{shared}, false})
}
func (h *Hub) memberLocked(token string) (Member, bool) {
	if _, e := h.root.Authenticate(token); e == nil {
		return h.state.Members["owner"], true
	}
	for id, s := range h.private {
		if _, e := s.st.Authenticate(token); e == nil {
			m := h.state.Members[id]
			return m, m.Active
		}
	}
	return Member{}, false
}
func (h *Hub) connections(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "请使用 POST")
		return
	}
	var in struct {
		DeviceName string `json:"device_name"`
		Protocol   string `json:"protocol"`
	}
	if !body(w, r, &in) {
		return
	}
	if in.Protocol != "family-spaces-v1" {
		failure(w, 409, "请先更新归账")
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	m, ok := h.memberLocked(bearer(r))
	if !ok || !h.state.Enabled {
		failure(w, 403, "家庭身份无效，请重新加入")
		return
	}
	if len(h.state.SharedDevices) >= 400 {
		failure(w, 409, "家庭设备数量已达上限，请在 NAS 连接维护页检查已连接设备")
		return
	}
	c, e := h.issueSharedLocked(m.ID, in.DeviceName)
	if e != nil {
		failure(w, 500, "连接家庭账本失败")
		return
	}
	if e = h.saveLocked(); e != nil {
		delete(h.state.SharedDevices, c.DeviceID)
		failure(w, 500, "保存连接失败")
		return
	}
	reply(w, 200, map[string]any{"member": m, "shared": []Connection{c}, "allow_member_edits": false})
}
func (h *Hub) revoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		failure(w, 405, "请使用 POST")
		return
	}
	if !h.owner(r) {
		failure(w, 403, "请在 NAS 连接维护面板断开成员连接")
		return
	}
	var in struct {
		ID string `json:"member_id"`
	}
	if !body(w, r, &in) {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	m, ok := h.state.Members[in.ID]
	if !ok || in.ID == "owner" {
		failure(w, 400, "无法停用这个成员")
		return
	}
	old := m
	m.Active = false
	h.state.Members[in.ID] = m
	if e := h.saveLocked(); e != nil {
		h.state.Members[in.ID] = old
		failure(w, 500, "保存失败，请重试")
		return
	}
	reply(w, 200, map[string]bool{"revoked": true})
}
func (h *Hub) serveSpace(w http.ResponseWriter, r *http.Request) {
	parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/family/space/"), "/", 3)
	if len(parts) != 3 || !validID(parts[1]) {
		http.NotFound(w, r)
		return
	}
	h.mu.RLock()
	defer h.mu.RUnlock() // A completed revocation excludes all subsequent space requests.
	var target *space
	actor := ""
	panelAPI := strings.HasPrefix(parts[2], "v1/panel/")
	publicUI := r.Method == "GET" && (parts[2] == "panel/" || parts[2] == "panel/import.js" || parts[2] == "panel/family.js" || parts[2] == "panel/edit.js")
	token := bearer(r)
	if token == "" && panelAPI {
		token = r.Header.Get("X-Panel-Token")
	}
	switch parts[0] {
	case "private":
		m, ok := h.state.Members[parts[1]]
		if !ok || !m.Active {
			failure(w, 403, "成员已停用")
			return
		}
		target = h.private[m.ID]
	case "shared":
		if !h.state.Enabled || parts[1] != h.state.SharedID {
			http.NotFound(w, r)
			return
		}
		target = h.shared
		if publicUI || (panelAPI && h.owner(r)) {
			if panelAPI {
				actor = "family-owner-admin"
			}
			break
		}
		d, e := target.st.Authenticate(token)
		if e != nil {
			failure(w, 401, "家庭账本连接无效")
			return
		}
		m, ok := h.state.Members[h.state.SharedDevices[d.ID]]
		if !ok || !m.Active {
			failure(w, 403, "你已不再是家庭成员")
			return
		}
		actor = d.ID
		if parts[2] == "v1/pair" || parts[2] == "v1/pair/code" {
			failure(w, 403, "请使用家庭成员邀请，不可分享整个家庭的配对码")
			return
		}
	default:
		http.NotFound(w, r)
		return
	}
	if target == nil {
		http.NotFound(w, r)
		return
	}
	copy := r.Clone(r.Context())
	if panelAPI {
		if parts[0] == "private" {
			if _, e := target.st.Authenticate(token); e != nil {
				failure(w, 401, "个人空间连接无效")
				return
			}
		}
		copy.Header.Set("X-Panel-Token", target.panelToken)
		// Never forward a caller-supplied actor identity.
		copy.Header.Set("X-Guizhang-Family-Actor", actor)
	}
	url := *r.URL
	url.Path = "/" + parts[2]
	copy.URL = &url
	target.handler.ServeHTTP(w, copy)
}
func seedShared(s *store.Store) error {
	change := func(entity, id string, l int64, data map[string]json.RawMessage) merge.Change {
		return merge.Change{ChangeID: "family-initial/" + entity + "/" + id, Entity: entity, EntityID: id, Op: merge.OpUpsert, Data: data, Lamport: l, Origin: "family-initial"}
	}
	changes := []merge.Change{change("ledger", "L1", 1, map[string]json.RawMessage{"payload": merge.V(`{"id":"L1","name":"家庭账本","kind":"SHARED"}`)})}
	for i, a := range []struct{ ID, Name, Kind string }{{"A-CASH", "现金", "CASH"}, {"A-WECHAT", "微信", "NETWORK"}, {"A-ALIPAY", "支付宝", "NETWORK"}, {"A-BANK", "家庭银行卡", "DEBIT"}} {
		changes = append(changes, change("account", a.ID, int64(2+i), map[string]json.RawMessage{"ledger_id": merge.V("L1"), "name": merge.V(a.Name), "kind": merge.V(a.Kind), "balance_minor": merge.V(0)}))
	}
	for i, n := range []string{"餐饮", "交通", "购物", "居家", "医疗", "娱乐", "教育", "通讯", "其他支出", "工资", "其他收入"} {
		kind := "EXPENSE"
		if i >= 9 {
			kind = "INCOME"
		}
		changes = append(changes, change("category", fmt.Sprintf("C-%d", i), int64(10+i), map[string]json.RawMessage{"ledger_id": merge.V("L1"), "name": merge.V(n), "kind": merge.V(kind)}))
	}
	result, e := s.Push("family-initial", changes)
	if e != nil {
		return e
	}
	if len(result.Rejected) > 0 {
		return errors.New("shared seed rejected")
	}
	return nil
}

func (h *Hub) EnableQuotes(service *quotes.Service) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.quotes = service
	if h.shared != nil {
		h.shared.panel.EnableQuotes(service)
	}
	for _, space := range h.private {
		space.panel.EnableQuotes(service)
	}
}
