// The publisher operates this separately. Never distribute the issuer or payment
// private keys with a customer's NAS image, compose file, or ledger backup.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"kapi/sync/internal/commerce"
	"kapi/sync/internal/pro"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func keyBytes(path string) ([]byte, error) {
	f, e := os.Open(path)
	if e != nil {
		return nil, e
	}
	defer f.Close()
	info, e := f.Stat()
	if e != nil {
		return nil, e
	}
	if !info.Mode().IsRegular() || info.Size() > 65536 || info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("key file must be a private regular file with mode 0600")
	}
	data, e := os.ReadFile(path)
	if e != nil {
		return nil, e
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, errors.New("invalid PEM key file")
	}
	return block.Bytes, nil
}
func ecKey(path string) (*ecdsa.PrivateKey, error) {
	b, e := keyBytes(path)
	if e != nil {
		return nil, e
	}
	if key, e := x509.ParseECPrivateKey(b); e == nil {
		return key, nil
	}
	key, e := x509.ParsePKCS8PrivateKey(b)
	if e != nil {
		return nil, e
	}
	value, ok := key.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("issuer key must be EC")
	}
	return value, nil
}
func rsaKey(path string) (*rsa.PrivateKey, error) {
	b, e := keyBytes(path)
	if e != nil {
		return nil, e
	}
	if key, e := x509.ParsePKCS1PrivateKey(b); e == nil {
		return key, nil
	}
	key, e := x509.ParsePKCS8PrivateKey(b)
	if e != nil {
		return nil, e
	}
	value, ok := key.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("payment key must be RSA")
	}
	return value, nil
}
func publicKey(path string) (*rsa.PublicKey, error) {
	b, e := keyBytes(path)
	if e != nil {
		return nil, e
	}
	if cert, e := x509.ParseCertificate(b); e == nil {
		if key, ok := cert.PublicKey.(*rsa.PublicKey); ok {
			return key, nil
		}
	}
	if key, e := x509.ParsePKCS1PublicKey(b); e == nil {
		return key, nil
	}
	key, e := x509.ParsePKIXPublicKey(b)
	if e != nil {
		return nil, e
	}
	value, ok := key.(*rsa.PublicKey)
	if !ok {
		return nil, errors.New("provider key must be RSA")
	}
	return value, nil
}
func config() (commerce.Config, error) {
	dir := os.Getenv("COMMERCE_DATA_DIR")
	if dir == "" {
		dir = "./commerce-data"
	}
	cfg := commerce.Config{Dir: dir, Enabled: os.Getenv("COMMERCE_PAYMENTS_ENABLED") == "true"}
	if cfg.Enabled && !pro.PricingCheckoutReady {
		return cfg, errors.New("new pricing is a closed preview; term, renewal and recovery acceptance is required before collecting payments")
	}
	if !cfg.Enabled && os.Getenv("COMMERCE_SIGNING_KEY_FILE") == "" {
		return cfg, nil
	}
	var e error
	cfg.SigningKey, e = ecKey(os.Getenv("COMMERCE_SIGNING_KEY_FILE"))
	if e != nil {
		return cfg, e
	}
	if os.Getenv("ALIPAY_APP_ID") != "" {
		private, e := rsaKey(os.Getenv("ALIPAY_PRIVATE_KEY_FILE"))
		if e != nil {
			return cfg, e
		}
		public, e := publicKey(os.Getenv("ALIPAY_PUBLIC_KEY_FILE"))
		if e != nil {
			return cfg, e
		}
		p, e := commerce.NewAlipay(commerce.AlipayConfig{AppID: os.Getenv("ALIPAY_APP_ID"), SellerID: os.Getenv("ALIPAY_SELLER_ID"), NotifyURL: os.Getenv("ALIPAY_NOTIFY_URL"), ReturnURL: os.Getenv("ALIPAY_RETURN_URL"), PrivateKey: private, PublicKey: public, Sandbox: os.Getenv("ALIPAY_SANDBOX") == "true"})
		if e != nil {
			return cfg, e
		}
		cfg.Providers = append(cfg.Providers, p)
	}
	if os.Getenv("WECHAT_APP_ID") != "" {
		private, e := rsaKey(os.Getenv("WECHAT_PRIVATE_KEY_FILE"))
		if e != nil {
			return cfg, e
		}
		public, e := publicKey(os.Getenv("WECHAT_PLATFORM_KEY_FILE"))
		if e != nil {
			return cfg, e
		}
		apiKey, e := os.ReadFile(os.Getenv("WECHAT_API_V3_KEY_FILE"))
		if e != nil {
			return cfg, e
		}
		p, e := commerce.NewWechat(commerce.WechatConfig{AppID: os.Getenv("WECHAT_APP_ID"), MerchantID: os.Getenv("WECHAT_MERCHANT_ID"), MerchantSerial: os.Getenv("WECHAT_MERCHANT_SERIAL"), PlatformSerial: os.Getenv("WECHAT_PLATFORM_SERIAL"), NotifyURL: os.Getenv("WECHAT_NOTIFY_URL"), PrivateKey: private, PlatformKey: public, APIv3Key: apiKey})
		if e != nil {
			return cfg, e
		}
		cfg.Providers = append(cfg.Providers, p)
	}
	if os.Getenv("AFDIAN_USER_ID") != "" {
		tokenPath := os.Getenv("AFDIAN_TOKEN_FILE")
		info, err := os.Stat(tokenPath)
		if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 || info.Size() > 4096 {
			return cfg, errors.New("Afdian token file must be private, regular and mode 0600")
		}
		token, err := os.ReadFile(tokenPath)
		if err != nil {
			return cfg, err
		}
		productType := 1
		if value := os.Getenv("AFDIAN_PRODUCT_TYPE"); value != "" {
			productType, err = strconv.Atoi(value)
			if err != nil {
				return cfg, errors.New("invalid Afdian product type")
			}
		}
		provider, err := commerce.NewAfdian(commerce.AfdianConfig{UserID: os.Getenv("AFDIAN_USER_ID"), Token: strings.TrimSpace(string(token)), PlanID: os.Getenv("AFDIAN_PLAN_ID"), SKUID: os.Getenv("AFDIAN_SKU_ID"), ProductType: productType, PermanentPlanConfirmed: os.Getenv("AFDIAN_PERMANENT_PLAN_CONFIRMED") == "true"})
		if err != nil {
			return cfg, err
		}
		cfg.Providers = append(cfg.Providers, provider)
	}

	return cfg, nil
}
func run() error {
	checkAfdian := flag.Bool("check-afdian", false, "read-only creator API check; emits counts only and never opens the order store")
	refundOrder := flag.String("record-afdian-refund-order", "", "record a platform refund already verified by the operator; does not transfer money")
	refundReference := flag.String("refund-reference", "", "unique actual platform refund reference")
	refundAmount := flag.Int64("refund-minor", 0, "actual refunded amount in CNY fen")
	flag.Parse()

	cfg, e := config()
	if e != nil {
		return e
	}
	if *checkAfdian {
		if *refundOrder != "" || *refundReference != "" || *refundAmount != 0 {
			return errors.New("connection check cannot be combined with refund recording")
		}
		for _, provider := range cfg.Providers {
			if p, ok := provider.(*commerce.Afdian); ok {
				count, err := p.CheckConnection(context.Background())
				if err != nil {
					return errors.New("Afdian read-only order query failed; no order or entitlement was changed")
				}
				return json.NewEncoder(os.Stdout).Encode(struct {
					Authenticated     bool `json:"authenticated"`
					OrdersOnFirstPage int  `json:"orders_on_first_page"`
					PaymentsEnabled   bool `json:"payments_enabled"`
					OrderStoreOpened  bool `json:"order_store_opened"`
				}{true, count, cfg.Enabled, false})
			}
		}
		return errors.New("Afdian provider is not configured")
	}
	s, e := commerce.New(cfg)
	if e != nil {
		return e
	}
	defer s.Close()
	if *refundOrder != "" {
		return s.RecordAfdianRefund(*refundOrder, *refundReference, *refundAmount)
	}
	if *refundReference != "" || *refundAmount != 0 {
		return errors.New("refund order required")
	}

	address := os.Getenv("COMMERCE_LISTEN")
	if address == "" {
		address = "127.0.0.1:8790"
	}
	server := &http.Server{Addr: address, Handler: commerce.NewHandler(s), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 25 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16384}
	signals, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	drained := make(chan struct{})
	go func() {
		<-signals.Done()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if server.Shutdown(ctx) != nil {
			_ = server.Close()
		}
		close(drained)
	}()
	log.Printf("commerce ready; payments_enabled=%t; order data separate from ledgers", cfg.Enabled)
	e = server.ListenAndServe()
	if errors.Is(e, http.ErrServerClosed) {
		<-drained
		return nil
	}
	return e
}
func main() {
	if e := run(); e != nil {
		log.Fatal(filepath.Base(os.Args[0]) + ": " + e.Error())
	}
}
