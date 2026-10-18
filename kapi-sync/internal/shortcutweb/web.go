// Package shortcutweb serves credential-free installation templates only.
package shortcutweb

import (
	"embed"
	"fmt"
	"net/http"
	"path"
	"strings"
)

//go:embed index.html files/*.shortcut
var assets embed.FS
var names = map[string]string{"setup.shortcut": "归账-连接NAS.shortcut", "capture.shortcut": "归账-截图记账.shortcut", "manual.shortcut": "归账-手动记账.shortcut", "resume.shortcut": "归账-继续任务.shortcut", "sync.shortcut": "归账-确认与同步.shortcut"}

func Register(mux *http.ServeMux) {
	mux.HandleFunc("/shortcuts/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" && r.Method != "HEAD" {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "只支持查看与下载", 405)
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'")
		if r.URL.Path == "/shortcuts/" {
			b, _ := assets.ReadFile("index.html")
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			if r.Method == "GET" {
				w.Write(b)
			}
			return
		}
		name := strings.TrimPrefix(r.URL.Path, "/shortcuts/files/")
		original, ok := names[name]
		if !ok || path.Base(name) != name {
			http.NotFound(w, r)
			return
		}
		b, e := assets.ReadFile("files/" + name)
		if e != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+urlName(original))
		w.Header().Set("Content-Length", fmt.Sprint(len(b)))
		if r.Method == "GET" {
			w.Write(b)
		}
	})
}
func urlName(s string) string {
	var b strings.Builder
	for _, c := range []byte(s) {
		if c > 127 {
			fmt.Fprintf(&b, "%%%02X", c)
		} else {
			b.WriteByte(c)
		}
	}
	return b.String()
}
