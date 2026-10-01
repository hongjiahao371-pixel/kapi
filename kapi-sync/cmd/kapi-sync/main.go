// Command kapi-sync is the self-hosted sync server for the kapi ledger app.
//
// It is designed to be run on a NAS with zero configuration:
//
//	docker run -d --name kapi-sync \
//	  -v /volume1/docker/kapi-sync:/data \
//	  -p 8787:8787 \
//	  kapi-sync
//
// On first start it creates /data, replays any existing log, prints a pairing
// code, and serves. There is no config file and no flags to get wrong.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"kapi/sync/internal/api"
	"kapi/sync/internal/panel"
	"kapi/sync/internal/store"
)

func main() {
	var (
		addr       = flag.String("addr", envOr("KAPI_ADDR", "0.0.0.0:8787"), "listen address")
		dataDir    = flag.String("data", envOr("KAPI_DATA", "/data"), "data directory")
		panelToken = flag.String("panel-token", os.Getenv("KAPI_PANEL_TOKEN"), "web panel token (generated if empty)")
		ver        = flag.Bool("version", false, "print version and exit")
	)
	flag.Parse()

	if *ver {
		fmt.Println("kapi-sync", store.Version)
		return
	}

	st, err := store.Open(*dataDir)
	if err != nil {
		log.Fatalf("无法打开数据目录 %s: %v", *dataDir, err)
	}
	defer st.Close()

	code, err := st.PairCode()
	if err != nil {
		log.Fatalf("生成配对码失败: %v", err)
	}

	// 面板挂在同一个端口上，路由和 /v1 共用一个 mux。令牌为空时生成一个，
	// 打进启动横幅——默认是"不告诉你就打不开"，而不是"局域网里谁都能翻账本"。
	ph, effectiveToken := panel.New(st, *panelToken)
	apiSrv := api.New(st)
	apiSrv.Mount(ph.Register)

	srv := &http.Server{
		Addr:              *addr,
		Handler:           logging(apiSrv),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("监听 %s 失败: %v", *addr, err)
	}

	printBanner(*addr, *dataDir, code, effectiveToken, st.HealthInfo())

	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("服务异常退出: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop

	log.Println("正在关闭,刷盘…")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("关闭超时: %v", err)
	}
	if err := st.Close(); err != nil {
		log.Printf("关闭数据文件失败: %v", err)
	}
}

func printBanner(addr, dataDir, code, panelToken string, h store.Health) {
	lan, bridge := localIPs()
	b, _ := json.Marshal(map[string]any{
		"version": h.ServerVer,
		"rows":    h.Rows,
		"seq":     h.DBSeq,
		"devices": h.DeviceCount,
	})
	port := "8787"
	if _, p, err := net.SplitHostPort(addr); err == nil {
		port = p
	}
	log.Printf("kapi-sync %s", store.Version)
	log.Printf("  数据目录   %s", dataDir)
	log.Printf("  监听地址   %s", addr)
	// 容器里只看得到容器自己的网卡。compose 默认网桥是 172.16.0.0/12，
	// 印出来的多半是 172.18.x 这种地址——用户把它填进手机，手机在局域网里
	// 根本路由不到，早期版本就是这么把人坑住的。所以分成两行，而且明确说
	// 哪一行才是要填的。
	//
	// KAPI_LAN_IP 是给"探测不到"准备的出口：容器没有宿主机的网卡视图，这是
	// 硬限制，不是 bug。与其让用户自己猜 NAS 的 IP，不如在 compose 里写死
	// ——反正 compose 文件本来就要改（改端口、改挂载）。
	override := strings.TrimSpace(os.Getenv("KAPI_LAN_IP"))
	switch {
	case len(lan) > 0:
		log.Printf("  局域网地址 %s  ← 填这个", strings.Join(lan, ", "))
	case override != "":
		log.Printf("  局域网地址 %s  ← 填这个（来自 KAPI_LAN_IP）", override)
	default:
		log.Printf("  局域网地址 未探测到。在 compose 里设 KAPI_LAN_IP=<NAS 的 IP>，")
		log.Printf("              容器看不到宿主机网卡，这个值只能由你告诉它。")
	}
	if len(bridge) > 0 {
		log.Printf("  (容器网段 %s 是 docker 网桥,填了连不上)", strings.Join(bridge, ", "))
	}
	log.Printf("  配对码     %s  (10 分钟内有效)", code)
	log.Printf("  面板令牌   %s", panelToken)
	log.Printf("  面板地址   http://<上面那个局域网地址>:%s/panel/", port)
	log.Printf("  当前状态   %s", b)
	log.Printf("在 App「设置 → 同步 → 添加服务器」中填入局域网地址和配对码。")
}

// localIPs 把网卡地址分成"像是真局域网"和"明显是容器网桥"两组。
//
// 判断依据是 RFC1918 的三段：172.16.0.0/12 几乎只被 docker/k8s 拿去当默认
// 网桥，192.168/16 和 10/8 则经常真的就是家里的局域网。分错了顶多让这一行
// 提示不准，不影响功能；不分开则会让用户拿到一个必然连不上的地址。
func localIPs() (lan, bridge []string) {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil, nil
	}
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok || ipnet.IP.IsLoopback() {
			continue
		}
		v4 := ipnet.IP.To4()
		if v4 == nil {
			continue
		}
		if v4[0] == 172 && v4[1] >= 16 && v4[1] <= 31 {
			bridge = append(bridge, v4.String())
		} else {
			lan = append(lan, v4.String())
		}
	}
	return lan, bridge
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

type statusRecorder struct {
	http.ResponseWriter
	code int
}

func (s *statusRecorder) WriteHeader(c int) {
	s.code = c
	s.ResponseWriter.WriteHeader(c)
}

func logging(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, code: 200}
		h.ServeHTTP(rec, r)
		// 早前这里把成功的 /v1/health 静音掉，理由是"配对向导会轮询，保持日志干净"。
		// 实际后果是：配对失败时服务端日志里**一条请求都没有**——因为向导里唯一
		// 确定能通的那一步，正好是唯一不打日志的那一步，排查时只能靠猜。
		// 配对是低频操作，多出来的几行完全值得。
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, rec.code, time.Since(start).Round(time.Millisecond))
	})
}
