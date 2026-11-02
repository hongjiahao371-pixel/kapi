// Package androiddist serves only publisher-signed Android distribution files.
// It never opens a ledger, requires a NAS purchase, or exposes pairing credentials.
package androiddist

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"

	"kapi/sync/internal/pro"
)

const GitHubReleases = "https://github.com/hongjiahao371-pixel/kapi/releases"
const SignatureContext = "Guizhang Android release v1\n"

type Manifest struct {
	Version     string `json:"version"`
	VersionCode int64  `json:"version_code"`
	Package     string `json:"package_name"`
	Filename    string `json:"filename"`
	Size        int64  `json:"size"`
	SHA256      string `json:"sha256"`
	Certificate string `json:"certificate_sha256"`
	GitHubURL   string `json:"github_url"`
}
type signedManifest struct {
	Payload   json.RawMessage `json:"payload"`
	Signature string          `json:"signature"`
}
type Handler struct {
	dir      string
	manifest *Manifest
}

func New(dir string) *Handler { return load(dir, pro.PublicKey) }
func load(dir, publicKey string) *Handler {
	h := &Handler{dir: dir}
	if dir == "" {
		return h
	}
	b, err := os.ReadFile(filepath.Join(dir, "release.json"))
	if err != nil || len(b) > 16384 {
		return h
	}
	var signed signedManifest
	if json.Unmarshal(b, &signed) != nil {
		return h
	}
	keyDER, err := base64.StdEncoding.DecodeString(publicKey)
	if err != nil {
		return h
	}
	parsed, err := x509.ParsePKIXPublicKey(keyDER)
	if err != nil {
		return h
	}
	key, ok := parsed.(*ecdsa.PublicKey)
	if !ok || key.Curve.Params().Name != "P-256" {
		return h
	}
	sig, err := base64.RawURLEncoding.DecodeString(signed.Signature)
	if err != nil {
		return h
	}
	sum := sha256.Sum256(append([]byte(SignatureContext), signed.Payload...))
	if !ecdsa.VerifyASN1(key, sum[:], sig) {
		return h
	}
	var m Manifest
	if json.Unmarshal(signed.Payload, &m) != nil || !regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+$`).MatchString(m.Version) || m.VersionCode <= 0 || m.Package != "kapi.ledger" || m.Filename != "guizhang-android-"+m.Version+".apk" || m.Size <= 0 || m.Size > 256<<20 || !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(m.SHA256) || !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(m.Certificate) || m.GitHubURL != GitHubReleases+"/download/android-v"+m.Version+"/"+m.Filename {
		return h
	}
	h.manifest = &m
	f, err := h.openVerified()
	if err != nil {
		h.manifest = nil
	} else {
		f.Close()
	}
	return h
}
func (h *Handler) openVerified() (*os.File, error) {
	if h.manifest == nil {
		return nil, errors.New("release unavailable")
	}
	path := filepath.Join(h.dir, h.manifest.Filename)
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() != h.manifest.Size {
		return nil, errors.New("invalid artifact")
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	hash := sha256.New()
	if n, e := io.Copy(hash, io.LimitReader(f, h.manifest.Size+1)); e != nil || n != h.manifest.Size || hex.EncodeToString(hash.Sum(nil)) != h.manifest.SHA256 {
		f.Close()
		return nil, errors.New("artifact checksum mismatch")
	}
	if _, err = f.Seek(0, io.SeekStart); err != nil {
		f.Close()
		return nil, err
	}
	return f, nil
}
func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("/android/", h.page)
	mux.HandleFunc("/android/apk", h.download)
	mux.HandleFunc("/v1/android/release", h.metadata)
}
func headers(w http.ResponseWriter) {
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Cache-Control", "no-store")
}
func getOnly(w http.ResponseWriter, r *http.Request) bool {
	headers(w)
	if r.Method != "GET" && r.Method != "HEAD" {
		w.Header().Set("Allow", "GET, HEAD")
		w.WriteHeader(405)
		return false
	}
	return true
}
func (h *Handler) metadata(w http.ResponseWriter, r *http.Request) {
	if !getOnly(w, r) {
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(struct {
		Available bool      `json:"available"`
		Release   *Manifest `json:"release,omitempty"`
		GitHub    string    `json:"github_releases"`
	}{h.manifest != nil, h.manifest, GitHubReleases})
}
func (h *Handler) download(w http.ResponseWriter, r *http.Request) {
	if !getOnly(w, r) {
		return
	}
	f, err := h.openVerified()
	if err != nil {
		http.Error(w, "安装包尚未提供或校验失败，请从 GitHub Releases 下载。", 503)
		return
	}
	defer f.Close()
	i, err := f.Stat()
	if err != nil {
		w.WriteHeader(503)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, h.manifest.Filename))
	w.Header().Set("ETag", `"`+h.manifest.SHA256+`"`)
	http.ServeContent(w, r, h.manifest.Filename, i.ModTime(), f)
}

var pageTemplate = template.Must(template.New("android").Parse(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>下载归账 Android</title><style>body{margin:0;background:#f4f6fa;color:#202632;font:16px/1.7 system-ui,sans-serif}main{max-width:660px;margin:52px auto;padding:0 22px}article{background:white;border:1px solid #e8ebf0;border-radius:28px;padding:28px;margin:18px 0}h1{font-size:32px;line-height:1.25}h2{font-size:19px}p{color:#566170}.button{display:inline-block;border-radius:24px;padding:12px 22px;background:#246bf0;color:white;text-decoration:none;margin:8px 8px 8px 0}.secondary{background:#edf3ff;color:#245aa8}code{overflow-wrap:anywhere;font-size:13px}small{color:#637083}a{color:#245aa8}</style><main><small>归账 · Android 独立分发</small><h1>把账本带到手机上</h1><p>本地记账可以独立使用。NAS 用于可选的同步、家庭共享和网页管理。</p><article>{{if .}}<h2>Android {{.Version}}</h2><p>安装包来自本镜像，下载无需购买 Pro。</p><a class="button" href="/android/apk">下载 APK</a><a class="button secondary" href="{{.GitHubURL}}" rel="noreferrer">从 GitHub 下载</a><p>包名 <code>{{.Package}}</code><br>SHA-256 <code>{{.SHA256}}</code></p><details><summary>签名证书信息</summary><code>{{.Certificate}}</code></details>{{else}}<h2>从 GitHub 获取安装包</h2><p>当前镜像没有附带通过校验的 APK，账本及同步服务可以继续使用。</p><a class="button" href="https://github.com/hongjiahao371-pixel/kapi/releases" rel="noreferrer">打开 GitHub Releases</a>{{end}}</article><article><h2>安装与升级</h2><p>请在 Android 手机上打开此页面下载，按系统提示允许此次安装来源。后续升级使用同包名、同签名的安装包。</p><p>原内测包 <code>kapi.ledger.debug</code> 与独立发行包 <code>kapi.ledger</code> 并存。换到独立发行包前，请保留旧版，导出完整备份，在新版恢复并核对个人账单；家庭账本重新连接原 NAS 和原成员身份。</p><p>不要同时开启两个包的自动记账和同步。内测赠送权益可联系支持免费领取迁移码。</p><a href="mailto:1981940361@qq.com" rel="noreferrer">使用帮助与支持</a></article><small>安装包下载不访问账单或配对令牌；本页不代表应用商店审核通过。</small></main></html>`))

func (h *Handler) page(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/android/" {
		http.NotFound(w, r)
		return
	}
	if !getOnly(w, r) {
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
	w.Header().Set("X-Frame-Options", "DENY")
	_ = pageTemplate.Execute(w, h.manifest)
}
