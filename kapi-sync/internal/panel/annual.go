package panel

import (
	"kapi/sync/internal/store"
	"net/http"
	"strconv"
	"time"
)

func (h *Handler) annualReport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(405)
		return
	}
	if !h.st.Pro.Allows("advanced_statistics") {
		writeJSON(w, 403, map[string]string{"error": "pro_required", "message": "年度报告需要 Pro，基础统计和账单导出继续免费"})
		return
	}
	year, err := strconv.Atoi(r.URL.Query().Get("year"))
	if err != nil || year < 2000 || year > 2100 {
		writeJSON(w, 400, map[string]string{"error": "invalid_year", "message": "请选择 2000 至 2100 年"})
		return
	}
	queries := make([]store.TxQuery, 12)
	for i := range queries {
		from := time.Date(year, time.Month(i+1), 1, 0, 0, 0, 0, time.Local)
		queries[i] = store.TxQuery{LedgerID: r.URL.Query().Get("ledger_id"), From: from.UnixMilli(), To: from.AddDate(0, 1, 0).UnixMilli()}
	}
	months := h.st.StatsBatch(queries)
	var income, expense int64
	for _, month := range months {
		income += month.Income
		expense += month.Expense
	}
	writeJSON(w, 200, map[string]any{"year": year, "ledger_id": r.URL.Query().Get("ledger_id"), "months": months, "income": income, "expense": expense, "balance": income - expense})
}
