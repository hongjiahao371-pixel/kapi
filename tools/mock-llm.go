// 假 OpenAI 端点。只为在模拟器上真跑一遍「选图 → 压缩 → 请求 → 解析 → 回填」。
//
// 为什么需要它：截图识别是这产品唯一的差异化功能，但本机没有任何真实大模型端点。
// 只验「连不上」的错误路径，等于只验了最容易的那一半——请求体有没有带上图、
// 回复有没有被 TransactionExtractor 抠出来、回填有没有把金额填对，一条都没验证。
//
// 用法：go run mock-llm.go -addr 0.0.0.0:8899
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
)

func main() {
	addr := flag.String("addr", "0.0.0.0:8899", "监听地址")
	flag.Parse()

	http.HandleFunc("/v1/chat/completions", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}

		// 关键断言：请求体里必须真的有图片。不能只看"没报错"就算通了。
		msgs, _ := body["messages"].([]any)
		hasImage := false
		imageLen := 0
		textChars := 0
		for _, m := range msgs {
			mm, _ := m.(map[string]any)
			content, _ := mm["content"].(any)
			switch c := content.(type) {
			case string:
				textChars += len(c)
			case []any:
				for _, part := range c {
					p, _ := part.(map[string]any)
					if p["type"] == "image_url" {
						hasImage = true
						iu, _ := p["image_url"].(map[string]any)
						u, _ := iu["url"].(string)
						imageLen = len(u)
					}
					if p["type"] == "text" {
						if t, ok := p["text"].(string); ok {
							textChars += len(t)
						}
					}
				}
			}
		}
		log.Printf("收到请求 model=%v 图片=%v base64长度=%d 文本长度=%d auth=%q",
			body["model"], hasImage, imageLen, textChars, r.Header.Get("Authorization"))

		reply := `{"amount": 23.50, "direction": "EXPENSE", "merchant": "星巴克", ` +
			`"category": "餐饮", "note": "拿铁 大杯", "occurred_at": "2026-09-29T15:30:00", ` +
			`"confidence": 0.92}`

		// 故意套一层 markdown 围栏 + 中文前后缀：真实模型就是这么回的，
		// TransactionExtractor 必须扛得住，不然这里测出来的成功是假的。
		content := "好的，我识别到这笔消费：\n```json\n" + reply + "\n```\n希望有帮助。"

		out := map[string]any{
			"id":      "mock-1",
			"object":  "chat.completion",
			"model":   body["model"],
			"choices": []any{map[string]any{
				"index":         0,
				"message":       map[string]any{"role": "assistant", "content": content},
				"finish_reason": "stop",
			}},
			"usage": map[string]any{"prompt_tokens": 100, "completion_tokens": 50},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	})

	// ping 用：只回一个字，别让客户端等
	http.HandleFunc("/v1/models", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintln(w, `{"object":"list","data":[]}`)
	})

	log.SetOutput(os.Stdout)
	log.Printf("mock-llm 监听 %s", *addr)
	log.Fatal(http.ListenAndServe(*addr, nil))
}

var _ = strings.TrimSpace
