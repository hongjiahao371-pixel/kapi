package panel

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"kapi/sync/internal/billimport"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type formatRequest struct {
	Ticket   string         `json:"ticket"`
	Revision int64          `json:"revision"`
	Mapping  map[string]int `json:"mapping"`
	BaseURL  string         `json:"baseURL"`
	Model    string         `json:"model"`
	APIKey   string         `json:"apiKey"`
}

func (h *Handler) importFormatPlan(w http.ResponseWriter, r *http.Request) {
	h.importFormat(w, r, "plan")
}
func (h *Handler) importFormatAI(w http.ResponseWriter, r *http.Request) { h.importFormat(w, r, "ai") }
func (h *Handler) importFormatApply(w http.ResponseWriter, r *http.Request) {
	h.importFormat(w, r, "apply")
}
func (h *Handler) importFormat(w http.ResponseWriter, r *http.Request, mode string) {
	if !h.importWriteAllowed(w, r) {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 16000)
	var b formatRequest
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if d.Decode(&b) != nil || d.Decode(&struct{}{}) != io.EOF {
		writeJSON(w, 400, map[string]string{"message": "字段分析参数无效"})
		return
	}
	svc := h.imports
	svc.mu.Lock()
	t := svc.tickets[b.Ticket]
	if t == nil || time.Now().After(t.Expires) {
		svc.mu.Unlock()
		writeJSON(w, 410, map[string]string{"message": "预览已过期，请重新选择文件"})
		return
	}
	if t.Saved != nil || t.Preview.Revision != b.Revision {
		svc.mu.Unlock()
		writeJSON(w, 409, map[string]string{"message": "预览已变化，请重新核对"})
		return
	}
	file, ledger := t.File, t.Preview.LedgerID
	svc.mu.Unlock()
	if mode != "apply" {
		plan, err := svc.worker.Run(r.Context(), billimport.Request{Mode: "field-plan", File: file})
		if err != nil {
			writeJSON(w, 400, map[string]string{"message": err.Error()})
			return
		}
		if mode == "plan" {
			writeJSON(w, 200, map[string]any{"prompt": plan.FieldPrompt, "mapping": plan.Mapping, "profile": plan.Profile})
			return
		}
		raw, err := requestImportFields(r.Context(), b, plan.FieldPrompt)
		if err != nil {
			writeJSON(w, 400, map[string]string{"message": err.Error()})
			return
		}
		var response struct {
			Mapping map[string]int `json:"mapping"`
		}
		raw = strings.TrimSpace(raw)
		if strings.HasPrefix(raw, "```") {
			raw = strings.TrimSpace(strings.TrimSuffix(raw[strings.Index(raw, "\n")+1:], "```"))
		}
		rd := json.NewDecoder(strings.NewReader(raw))
		rd.DisallowUnknownFields()
		if rd.Decode(&response) != nil || rd.Decode(&struct{}{}) != io.EOF || response.Mapping == nil {
			writeJSON(w, 400, map[string]string{"message": "AI 未返回有效列映射，本地结果未改变"})
			return
		}
		// The shared parser validates indices and financial field conflicts, before showing a proposal.
		if _, err = svc.worker.Run(r.Context(), billimport.Request{Mode: "field-apply", File: file, Mapping: response.Mapping}); err != nil {
			writeJSON(w, 400, map[string]string{"message": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]any{"mapping": response.Mapping, "message": "仅为建议，采用并重新预览后仍需核对"})
		return
	}
	if len(b.Mapping) == 0 || len(b.Mapping) > 20 {
		writeJSON(w, 400, map[string]string{"message": "请先填写字段映射"})
		return
	}
	parsed, err := svc.worker.Run(r.Context(), billimport.Request{Mode: "field-apply", File: file, Mapping: b.Mapping})
	if err != nil {
		writeJSON(w, 400, map[string]string{"message": err.Error()})
		return
	}
	_, cs, catalog, err := svc.match(r.Context(), ledger, parsed.Rows)
	if err != nil {
		writeJSON(w, 400, map[string]string{"message": err.Error()})
		return
	}
	svc.mu.Lock()
	defer svc.mu.Unlock()
	if svc.tickets[b.Ticket] != t || t.Saved != nil || t.Preview.Revision != b.Revision || time.Now().After(t.Expires) {
		writeJSON(w, 409, map[string]string{"message": "预览已变化，请重新核对"})
		return
	}
	parsed.Rows = nil
	t.Mapping = b.Mapping
	t.Edits = nil
	t.Preview.Parsed = parsed
	t.Preview.Candidates = cs
	t.Preview.Catalog = catalog
	t.Preview.Revision++
	writeJSON(w, 200, t.Preview)
}

// Admin-supplied endpoint; config and key are used for this call only, never persisted or logged.
func requestImportFields(ctx context.Context, c formatRequest, prompt string) (string, error) {
	fail := func() (string, error) {
		return "", errors.New("AI 字段分析失败，请核对接口地址、模型和 Key；本地预览未改变")
	}
	if len(c.BaseURL) > 2048 || len(c.Model) > 200 || len(c.APIKey) > 4096 || strings.TrimSpace(c.Model) == "" {
		return fail()
	}
	u, e := url.Parse(strings.TrimSpace(c.BaseURL))
	if e != nil || u.Host == "" || u.User != nil || u.Fragment != "" || u.RawQuery != "" || (u.Scheme != "https" && u.Scheme != "http") {
		return fail()
	}
	kind := "chat"
	path := strings.TrimSuffix(u.Path, "/")
	host := strings.ToLower(u.Hostname())
	if strings.HasSuffix(path, "/responses") {
		kind = "responses"
	} else if strings.HasSuffix(path, "/messages") || host == "api.anthropic.com" {
		kind = "claude"
	} else if strings.HasSuffix(path, ":generateContent") || host == "generativelanguage.googleapis.com" && !strings.Contains(path, "openai") {
		kind = "gemini"
	}
	var body any
	switch kind {
	case "chat":
		if !strings.HasSuffix(path, "/chat/completions") {
			if path == "" {
				path = "/v1"
			}
			path += "/chat/completions"
		}
		body = map[string]any{"model": c.Model, "max_tokens": 2000, "messages": []any{map[string]string{"role": "user", "content": prompt}}}
	case "responses":
		body = map[string]any{"model": c.Model, "store": false, "max_output_tokens": 2000, "input": prompt}
	case "claude":
		if !strings.HasSuffix(path, "/messages") {
			if path == "" {
				path = "/v1"
			}
			path += "/messages"
		}
		body = map[string]any{"model": c.Model, "max_tokens": 2000, "messages": []any{map[string]string{"role": "user", "content": prompt}}}
	case "gemini":
		if !strings.HasSuffix(path, ":generateContent") {
			if path == "" {
				path = "/v1beta"
			}
			path += "/models/" + strings.TrimPrefix(c.Model, "models/") + ":generateContent"
		}
		body = map[string]any{"contents": []any{map[string]any{"role": "user", "parts": []any{map[string]string{"text": prompt}}}}, "generationConfig": map[string]int{"maxOutputTokens": 2000}}
	}
	u.Path = path
	data, _ := json.Marshal(body)
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	req, e := http.NewRequestWithContext(ctx, "POST", u.String(), bytes.NewReader(data))
	if e != nil {
		return fail()
	}
	req.Header.Set("Content-Type", "application/json")
	key := strings.TrimSpace(c.APIKey)
	if key != "" {
		switch kind {
		case "claude":
			req.Header.Set("x-api-key", key)
		case "gemini":
			req.Header.Set("x-goog-api-key", key)
		default:
			if strings.HasSuffix(host, ".openai.azure.com") || strings.HasSuffix(host, ".services.ai.azure.com") {
				req.Header.Set("api-key", key)
			} else {
				req.Header.Set("Authorization", "Bearer "+key)
			}
		}
	}
	if kind == "claude" {
		req.Header.Set("anthropic-version", "2023-06-01")
	}
	client := &http.Client{Timeout: 45 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, e := client.Do(req)
	if e != nil {
		return fail()
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fail()
	}
	raw, e := io.ReadAll(io.LimitReader(resp.Body, 128*1024+1))
	if e != nil || len(raw) > 128*1024 {
		return fail()
	}
	var root struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
		Content []struct {
			Text string `json:"text"`
		} `json:"content"`
		Output []struct {
			Content []struct {
				Text string `json:"text"`
			} `json:"content"`
		} `json:"output"`
		Candidates []struct {
			Content struct {
				Parts []struct {
					Text string `json:"text"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
	}
	if json.Unmarshal(raw, &root) != nil {
		return fail()
	}
	text := ""
	switch kind {
	case "chat":
		if len(root.Choices) > 0 {
			text = root.Choices[0].Message.Content
		}
	case "claude":
		for _, p := range root.Content {
			text += p.Text
		}
	case "responses":
		for _, o := range root.Output {
			for _, p := range o.Content {
				text += p.Text
			}
		}
	case "gemini":
		if len(root.Candidates) > 0 {
			for _, p := range root.Candidates[0].Content.Parts {
				text += p.Text
			}
		}
	}
	if text == "" || len(text) > 16000 {
		return fail()
	}
	return text, nil
}
