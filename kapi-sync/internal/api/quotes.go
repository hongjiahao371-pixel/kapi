package api

import (
	"context"
	"errors"
	"kapi/sync/internal/quotes"
	"kapi/sync/internal/store"
	"net/http"
	"strings"
	"time"
)

func (s *Server) EnableQuotes(q *quotes.Service) {
	s.mux.HandleFunc("/v1/investment/quotes", s.auth(func(w http.ResponseWriter, r *http.Request, _ *store.Device) {
		if r.Method != "GET" && r.Method != "POST" {
			methodNotAllowed(w, "GET, POST")
			return
		}
		symbols := strings.Split(r.URL.Query().Get("symbols"), ",")
		if len(symbols) == 1 && symbols[0] == "" {
			symbols = nil
		}
		var rows []quotes.Quote
		var e error
		if r.Method == "POST" {
			ctx, cancel := context.WithTimeout(r.Context(), 18*time.Second)
			rows, e = q.Refresh(ctx, symbols)
			cancel()
		} else {
			rows, e = q.Get(symbols, false)
		}
		if e != nil {
			if errors.Is(e, context.DeadlineExceeded) || errors.Is(e, context.Canceled) {
				writeErr(w, 504, "quote_timeout", "行情更新超时，请稍后重试")
			} else {
				writeErr(w, 400, "bad_symbols", e.Error())
			}
			return
		}
		writeJSON(w, 200, map[string]any{"quotes": rows})
	}))
}
