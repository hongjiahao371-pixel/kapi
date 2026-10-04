// Package panel serves the read-only web UI that lives next to the sync API.
//
// # Why a panel at all
//
// The phone is the only writer, but the NAS holds the authoritative copy and is
// the only thing that survives the phone being lost. A user who wants to check
// "did that payment from last Tuesday make it in" currently has no way to do
// that without SSH. This is the answer, and it is deliberately read-only: the
// merge rules assume a single writer with a Lamport clock, and a browser
// editing rows would be a second writer with no clock.
//
// # Auth
//
// The panel is a full ledger dump — every merchant, every amount. It is gated
// on a token, not on the device pairing system, because the pairing tokens
// belong to phones and a browser has nowhere to put a 64-character bearer that
// a user would have to paste out of logcat.
//
// The token comes from KAPI_PANEL_TOKEN. If that is unset the server generates
// one at startup and prints it in the banner, so the default is "you must know
// something to get in" rather than "anyone on the LAN can read your ledger".
// Setting the env var is how you make it survive a container restart with a
// token you already know.
//
// The HTML shell is served unauthenticated on purpose: it is a login form and
// contains no data. Everything that returns ledger contents is behind the token.
package panel

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"kapi/sync/internal/store"
)

//go:embed web/index.html
var assets embed.FS

// Handler serves the panel shell and its JSON endpoints.
type Handler struct {
	st    *store.Store
	token string
}

// New builds a Handler. If token is empty a random one is generated and
// returned as second so the caller can print it.
func New(st *store.Store, token string) (*Handler, string) {
	if token == "" {
		token = randomToken()
	}
	return &Handler{st: st, token: token}, token
}

func randomToken() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		// crypto/rand failing is not recoverable in any way that leaves this
		// server safe to expose, so refuse to invent a weak token.
		panic("panel: 无法生成访问令牌: " + err.Error())
	}
	return hex.EncodeToString(b)
}

// Register mounts every panel route on mux.
func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("/panel", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/panel/", http.StatusFound)
	})
	mux.HandleFunc("/panel/", h.shell)
	mux.HandleFunc("/v1/panel/summary", h.auth(h.summary))
	mux.HandleFunc("/v1/panel/tx", h.auth(h.tx))
	mux.HandleFunc("/v1/panel/row", h.auth(h.row))
	mux.HandleFunc("/v1/panel/facets", h.auth(h.facets))
	// 配对码重新生成。放面板而不只在启动日志里打，是因为配对码 10 分钟
	// 过期，而容器可能几个月才重启一次——"手机就在手上、就想现在配"的
	// 时刻，重启容器捞日志这条路是走不通的。
	mux.HandleFunc("/v1/panel/pair_code", h.auth(h.pairCode))
}

func (h *Handler) shell(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/panel/" {
		http.NotFound(w, r)
		return
	}
	b, err := assets.ReadFile("web/index.html")
	if err != nil {
		http.Error(w, "panel asset missing: "+err.Error(), 500)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	// The shell is a login form; caching it would only make the token prompt
	// stale after a restart. The data endpoints are separately uncached.
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Frame-Options", "DENY")
	_, _ = w.Write(b)
}

func (h *Handler) auth(next func(http.ResponseWriter, *http.Request)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := r.URL.Query().Get("token")
		if tok == "" {
			tok = r.Header.Get("X-Panel-Token")
		}
		// Constant-time compare. A ledger is worth guessing for, and a
		// byte-by-byte early-return comparison leaks the prefix length of a
		// 32-char hex token to anyone willing to make a few million requests.
		if tok == "" || !constantTimeEqual(tok, h.token) {
			writeJSON(w, http.StatusUnauthorized, map[string]string{
				"error": "unauthorized",
				// Say where to get it. Someone standing in their own hallway
				// with the NAS in front of them failing to log in needs the
				// docker logs command, not a bare 401.
				"message": "面板令牌不对。运行 docker logs kapi-sync 可以看到本次启动的面板令牌," +
					"或者在 compose 里设置 KAPI_PANEL_TOKEN 固定它。",
			})
			return
		}
		next(w, r)
	}
}

func constantTimeEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var v byte
	for i := 0; i < len(a); i++ {
		v |= a[i] ^ b[i]
	}
	return v == 0
}

// query builds a store.TxQuery from the request.
//
// `from`/`to` are **dates** ("2026-09-01"), not timestamps, because that is what
// a person types. `to` is made exclusive at the next midnight so a range of
// 09-01..09-30 includes the whole of the 30th instead of stopping at 00:00.
func (h *Handler) query(r *http.Request) store.TxQuery {
	q := store.TxQuery{
		Keyword:        strings.TrimSpace(r.URL.Query().Get("q")),
		Direction:      r.URL.Query().Get("direction"),
		CategoryID:     r.URL.Query().Get("category_id"),
		AccountID:      r.URL.Query().Get("account_id"),
		IncludeDeleted: r.URL.Query().Get("include_deleted") == "1",
		Offset:         atoiDefault(r.URL.Query().Get("offset"), 0),
		Limit:          atoiDefault(r.URL.Query().Get("limit"), 0),
	}
	if d := r.URL.Query().Get("from"); d != "" {
		q.From = startOfDay(d)
	}
	if d := r.URL.Query().Get("to"); d != "" {
		q.To = startOfDayNext(d)
	}
	// The phone only ever writes EXPENSE/INCOME. An unknown direction from a
	// hand-edited request would otherwise silently mean "no filter".
	if q.Direction != store.DirExpense && q.Direction != store.DirIncome {
		q.Direction = ""
	}
	return q
}

// pairCode 重新生成 6 位配对码并返回。配对本身仍走 POST /v1/pair（免鉴权、
// 靠码换 token），这里只是把"码从哪来"从启动日志挪到了面板。
func (h *Handler) pairCode(w http.ResponseWriter, r *http.Request) {
	code, err := h.st.PairCode()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error":   "rotate_failed",
			"message": err.Error(),
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"pair_code": code})
}

func (h *Handler) summary(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"stats":  h.st.Stats(h.query(r)),
		"health": h.st.HealthInfo(),
	})
}

func (h *Handler) tx(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, h.st.ListTx(h.query(r)))
}

func (h *Handler) facets(w http.ResponseWriter, r *http.Request) {
	d := r.URL.Query().Get("dimension")
	out := h.st.Facets(d)
	if out == nil {
		out = []store.Facet{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"dimension": d, "items": out})
}

// row returns the raw merged record, field versions included.
//
// The list view is for reading money. This one is for the question that comes
// up when two devices disagree: "which device last wrote this amount, and
// when". That information only exists in the per-field Lamport stamps, and
// hiding them would mean answering that question by reading log.jsonl by hand.
func (h *Handler) row(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if id == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_request", "message": "缺少 id"})
		return
	}
	rec, ok := h.st.Row(store.TxnEntity, id)
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "not_found", "message": "没有这一行"})
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

func atoiDefault(s string, def int) int {
	if s == "" {
		return def
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	return n
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
