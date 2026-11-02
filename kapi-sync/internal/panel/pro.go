package panel

import (
	"encoding/json"
	"net/http"
)

func (h *Handler) proStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		w.WriteHeader(405)
		return
	}
	status := h.st.Pro.Status()
	status.OrderRestoreAvailable = h.commerceURL != ""
	writeJSON(w, 200, status)
}
func (h *Handler) proActivate(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		w.WriteHeader(405)
		return
	}
	var in struct {
		Code string `json:"code"`
	}
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192))
	d.DisallowUnknownFields()
	if d.Decode(&in) != nil {
		writeJSON(w, 400, map[string]string{"error": "invalid_request", "message": "请提供完整激活码"})
		return
	}
	if e := h.st.Pro.Activate(in.Code); e != nil {
		writeJSON(w, 400, map[string]string{"error": "invalid_license", "message": "激活码无效，原有权益与账本已保留"})
		return
	}
	status := h.st.Pro.Status()
	status.OrderRestoreAvailable = h.commerceURL != ""
	writeJSON(w, 200, status)
}
