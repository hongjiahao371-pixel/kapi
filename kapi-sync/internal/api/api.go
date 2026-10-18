// Package api exposes the sync server over HTTP.
//
// Auth model: every /v1 route except /v1/health and /v1/pair requires
// `Authorization: Bearer <token>`. Health is unauthenticated on purpose — it
// is the first call the Android pairing wizard makes, and a wizard that
// cannot reach health cannot produce a useful error message. It exposes no
// user data, only row counts and the current sequence number.
package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"path/filepath"
	"strconv"

	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
)

// Server wires the HTTP handlers to a store.
type Server struct {
	st  *store.Store
	mux *http.ServeMux
}

// New builds the router.
func New(st *store.Store) *Server {
	s := &Server{st: st, mux: http.NewServeMux()}

	s.mux.HandleFunc("/v1/health", s.handleHealth)
	s.mux.HandleFunc("/v1/pair", s.handlePair)
	s.mux.HandleFunc("/v1/pair/code", s.auth(s.handlePairCode))
	s.mux.HandleFunc("/v1/changes", s.auth(s.handleChanges))
	s.mux.HandleFunc("/v1/push", s.auth(s.handlePush))
	s.mux.HandleFunc("/v1/row", s.auth(s.handleRow))
	s.mux.HandleFunc("/v1/backup", s.auth(s.handleBackup))
	s.mux.HandleFunc("/v1/backups", s.auth(s.handleBackups))
	s.mux.HandleFunc("/v1/shortcuts/catalog", s.auth(s.handleShortcutCatalog))
	s.mux.HandleFunc("/v1/shortcuts/preview", s.auth(s.handleShortcutPreview))
	s.mux.HandleFunc("/v1/shortcuts/commit", s.auth(s.handleShortcutCommit))
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mux.ServeHTTP(w, r)
}

// Mount lets another package register routes on the same mux.
//
// The web panel is served by the same process and the same port on purpose:
// splitting it onto a second listener would mean a second port in the pairing
// wizard, a second thing to firewall, and a second failure mode to explain to
// someone whose sync is already not working. Sharing the mux also means the
// panel inherits the access log, which is how you find out that a client is
// hammering /v1/panel/tx.
func (s *Server) Mount(register func(*http.ServeMux)) { register(s.mux) }

// ---------------------------------------------------------------------------

type ctxKey string

const deviceKey ctxKey = "device"

type authedHandler func(http.ResponseWriter, *http.Request, *store.Device)

func (s *Server) auth(next authedHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := bearer(r)
		if tok == "" {
			writeErr(w, http.StatusUnauthorized, "missing_token", "Authorization: Bearer <token> required")
			return
		}
		d, err := s.st.Authenticate(tok)
		if err != nil {
			writeErr(w, http.StatusUnauthorized, "invalid_token",
				"令牌无效,重新配对一次。请在 App「设置 → 同步 → 断开并重新配对」。")
			return
		}
		next(w, r, d)
	}
}

func bearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	const p = "Bearer "
	if len(h) > len(p) && h[:len(p)] == p {
		return h[len(p):]
	}
	return ""
}

// ---------------------------------------------------------------------------
// handlers
// ---------------------------------------------------------------------------

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	h := s.st.HealthInfo()
	// Health is public connectivity metadata, never a source of pairing credentials.
	// Pairing codes are available only through authenticated device/panel endpoints.
	writeJSON(w, http.StatusOK, h)
}

func (s *Server) handlePairCode(w http.ResponseWriter, r *http.Request, _ *store.Device) {
	code, err := s.st.PairCode()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "rotate_failed", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"pair_code": code})
}

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, "POST")
		return
	}
	var body struct {
		PairCode   string `json:"pair_code"`
		DeviceName string `json:"device_name"`
	}
	if err := decode(r, &body); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_body", err.Error())
		return
	}
	res, err := s.st.Pair(body.PairCode, body.DeviceName)
	if err != nil {
		if errors.Is(err, store.ErrPairCodeInvalid) {
			writeErr(w, http.StatusForbidden, "pair_code_invalid",
				"配对码无效或已过期。在 NAS 日志或管理页重新获取,有效期 10 分钟。")
			return
		}
		writeErr(w, http.StatusInternalServerError, "pair_failed", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) handleChanges(w http.ResponseWriter, r *http.Request, d *store.Device) {
	since, err := int64Param(r, "since", 0)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_since", "since 必须是非负整数")
		return
	}
	limit64, err := int64Param(r, "limit", 200)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_limit", "limit 必须是 1..1000 的整数")
		return
	}
	entries, cursor, hasMore, err := s.st.Changes(since, int(limit64))
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if entries == nil {
		entries = []store.LogEntry{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"changes":  entries,
		"cursor":   cursor,
		"has_more": hasMore,
	})
}

type pushRequest struct {
	DeviceID string         `json:"device_id"`
	Changes  []merge.Change `json:"changes"`
}

func (s *Server) handlePush(w http.ResponseWriter, r *http.Request, d *store.Device) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, "POST")
		return
	}
	var body pushRequest
	if err := decode(r, &body); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_body", err.Error())
		return
	}
	// The authenticated device is authoritative. A body claiming a different
	// device_id is a client bug; trusting it would let one device write
	// another device's id into the merge stamps.
	if body.DeviceID != "" && body.DeviceID != d.ID {
		writeErr(w, http.StatusForbidden, "device_mismatch",
			"device_id 与令牌不匹配,已使用令牌对应的设备")
		return
	}
	res, err := s.st.Push(d.ID, body.Changes)
	if err != nil {
		switch {
		case errors.Is(err, store.ErrEmptyPush):
			writeErr(w, http.StatusBadRequest, "empty_push", "changes 不能为空")
		case errors.Is(err, store.ErrWriteForbidden):
			writeErr(w, http.StatusForbidden, "family_permission", "只能修改或删除自己记录的家庭账单")
		default:
			writeErr(w, http.StatusInternalServerError, "push_failed", err.Error())
		}
		return
	}
	writeJSON(w, http.StatusOK, res)
}

// handleRow is a debug aid for the "which device wrote this?" question that
// comes up the first time two devices disagree. Read-only.
func (s *Server) handleRow(w http.ResponseWriter, r *http.Request, _ *store.Device) {
	entity := r.URL.Query().Get("entity")
	id := r.URL.Query().Get("id")
	row, ok := s.st.Row(entity, id)
	if !ok {
		writeErr(w, http.StatusNotFound, "not_found", "no such row")
		return
	}
	writeJSON(w, http.StatusOK, row)
}

// ---------------------------------------------------------------------------

func decode(r *http.Request, v any) error {
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 32<<20))
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

func int64Param(r *http.Request, name string, def int64) (int64, error) {
	raw := r.URL.Query().Get(name)
	if raw == "" {
		return def, nil
	}
	return strconv.ParseInt(raw, 10, 64)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, kind, msg string) {
	writeJSON(w, code, map[string]string{"error": kind, "message": msg})
}

func methodNotAllowed(w http.ResponseWriter, allow string) {
	w.Header().Set("Allow", allow)
	writeErr(w, http.StatusMethodNotAllowed, "method_not_allowed", "只支持 "+allow)
}

func (s *Server) handleBackup(w http.ResponseWriter, r *http.Request, _ *store.Device) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, "GET")
		return
	}
	path, err := s.st.CreateBackup(true)
	if err != nil {
		writeErr(w, 500, "backup_failed", err.Error())
		return
	}
	w.Header().Set("Content-Disposition", "attachment; filename=\""+filepath.Base(path)+"\"")
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, path)
}
func (s *Server) handleBackups(w http.ResponseWriter, r *http.Request, _ *store.Device) {
	items, err := s.st.Backups()
	if err != nil {
		writeErr(w, 500, "backup_failed", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"items": items})
}
