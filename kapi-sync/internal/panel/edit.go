package panel

import (
	"encoding/json"
	"errors"
	"io"
	"kapi/sync/internal/store"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

var decimalAmount = regexp.MustCompile(`^(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,2})?$`)

func (h *Handler) editTransaction(w http.ResponseWriter, r *http.Request) {
	actor := r.Header.Get("X-Guizhang-Family-Actor")
	if r.Method == http.MethodGet {
		editor, err := h.st.TransactionEditor(r.URL.Query().Get("id"), actor)
		if err != nil {
			writeJSON(w, 404, map[string]string{"message": err.Error()})
			return
		}
		writeJSON(w, 200, editor)
		return
	}
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "GET, POST")
		w.WriteHeader(405)
		return
	}
	if !constantTimeEqual(r.Header.Get("X-Panel-Token"), h.token) {
		writeJSON(w, 401, map[string]string{"message": "请重新登录面板"})
		return
	}
	if raw := r.Header.Get("Origin"); raw != "" {
		u, err := url.Parse(raw)
		if err != nil || u.Host != r.Host || u.Scheme != "http" && u.Scheme != "https" {
			writeJSON(w, 403, map[string]string{"message": "请从 NAS 面板保存账单"})
			return
		}
	}
	if r.Header.Get("Sec-Fetch-Site") == "cross-site" {
		w.WriteHeader(403)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 32*1024)
	var body struct {
		ID        string  `json:"id"`
		Revision  string  `json:"revision"`
		RequestID string  `json:"request_id"`
		Amount    *string `json:"amount"`
		Category  *string `json:"category_id"`
		Account   *string `json:"account_id"`
		Note      *string `json:"note"`
	}
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if decoder.Decode(&body) != nil || decoder.Decode(new(any)) != io.EOF || body.Amount == nil || body.Category == nil || body.Account == nil || body.Note == nil {
		writeJSON(w, 400, map[string]string{"message": "请完整填写金额、分类、账户和备注"})
		return
	}
	amount := strings.TrimSpace(*body.Amount)
	if !decimalAmount.MatchString(amount) {
		writeJSON(w, 400, map[string]string{"message": "请输入最多两位小数的正数金额"})
		return
	}
	parts := strings.Split(amount, ".")
	fraction := "00"
	if len(parts) == 2 {
		fraction = (parts[1] + "00")[:2]
	}
	major, _ := strconv.ParseInt(parts[0], 10, 64)
	minor, _ := strconv.ParseInt(fraction, 10, 64)
	saved, err := h.st.EditTransaction(actor, store.TransactionEdit{ID: body.ID, Revision: body.Revision, RequestID: body.RequestID, AmountMinor: major*100 + minor, CategoryID: *body.Category, AccountID: *body.Account, Note: strings.TrimSpace(*body.Note)})
	if err != nil {
		status := 400
		switch {
		case errors.Is(err, store.ErrEditConflict), errors.Is(err, store.ErrEditRequestReuse):
			status = 409
		case errors.Is(err, store.ErrWriteForbidden):
			status = 403
		case errors.Is(err, store.ErrEditNotFound):
			status = 404
		}
		writeJSON(w, status, map[string]string{"message": err.Error()})
		return
	}
	writeJSON(w, 200, saved)
}
