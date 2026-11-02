// Package commerce is the publisher's order service, never part of a user's
// ledger. Only a verified provider result may issue a signed Pro activation.
package commerce

import (
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"kapi/sync/internal/pro"
)

var ErrClosed = errors.New("payments_unavailable")
var ErrNotFound = errors.New("order_not_found")
var ErrConflict = errors.New("order_conflict")
var ErrInvalid = errors.New("invalid_request")
var ErrPendingOrder = errors.New("order_requires_reconciliation")

type Checkout struct {
	Kind  string `json:"kind"`
	Value string `json:"value"`
}
type Receipt struct {
	OrderID, TradeID, Currency, State string
	AmountMinor                       int64
	RefundID                          string
	RefundMinor                       int64
}
type Provider interface {
	Name() string
	Prepare(context.Context, Order) (Checkout, error)
	Query(context.Context, Order) (Receipt, error)
}
type Order struct {
	ID          string   `json:"id"`
	ProductID   string   `json:"product_id"`
	Platform    string   `json:"platform"`
	Channel     string   `json:"channel"`
	AmountMinor int64    `json:"amount_minor"`
	Currency    string   `json:"currency"`
	State       string   `json:"state"`
	CreatedAt   int64    `json:"created_at"`
	ExpiresAt   int64    `json:"expires_at"`
	UpdatedAt   int64    `json:"updated_at"`
	OwnerHash   string   `json:"owner_hash"`
	RequestID   string   `json:"request_id"`
	TradeID     string   `json:"trade_id,omitempty"`
	License     string   `json:"license,omitempty"`
	Checkout    Checkout `json:"checkout"`
	Revision    int64    `json:"revision,omitempty"`
	Refunds     []Refund `json:"refunds,omitempty"`
}
type OrderView struct {
	ID               string   `json:"id"`
	ProductID        string   `json:"product_id"`
	Platform         string   `json:"platform"`
	Channel          string   `json:"channel"`
	AmountMinor      int64    `json:"amount_minor"`
	Currency         string   `json:"currency"`
	State            string   `json:"state"`
	CreatedAt        int64    `json:"created_at"`
	ExpiresAt        int64    `json:"expires_at"`
	Checkout         Checkout `json:"checkout"`
	License          string   `json:"activation_code,omitempty"`
	EntitlementProof string   `json:"entitlement_proof,omitempty"`
}

func view(o Order) OrderView {
	v := OrderView{ID: o.ID, ProductID: o.ProductID, Platform: o.Platform, Channel: o.Channel, AmountMinor: o.AmountMinor, Currency: o.Currency, State: o.State, CreatedAt: o.CreatedAt, ExpiresAt: o.ExpiresAt, Checkout: o.Checkout}
	if o.State == "PAID" {
		v.License = o.License
	}
	return v
}

type diskState struct {
	Version int              `json:"version"`
	Orders  map[string]Order `json:"orders"`
}
type Config struct {
	Dir               string
	Enabled           bool
	SigningKey        *ecdsa.PrivateKey
	ExpectedPublicKey string
	AllowNasPurchases bool // Disabled for the first Android-only paid release.
	Providers         []Provider
}
type Service struct {
	mu                sync.Mutex
	file              string
	lock              *os.File
	state             diskState
	enabled           bool
	key               *ecdsa.PrivateKey
	providers         map[string]Provider
	now               func() time.Time
	damaged           bool
	allowNasPurchases bool
}

func New(cfg Config) (*Service, error) {
	if cfg.Dir == "" {
		return nil, ErrInvalid
	}
	for _, name := range []string{"log.jsonl", "entitlements.json", "kapi.db"} {
		if _, e := os.Stat(filepath.Join(cfg.Dir, name)); e == nil {
			return nil, errors.New("commerce requires a separate directory from ledger data")
		}
	}
	if cfg.Enabled && (cfg.SigningKey == nil || len(cfg.Providers) == 0) {
		return nil, errors.New("payments enabled without issuer/provider configuration")
	}
	if cfg.SigningKey != nil {
		if cfg.Enabled && len(cfg.Providers) == 0 {
			return nil, errors.New("payments enabled without issuer/provider configuration")
		}
		if cfg.SigningKey.Curve.Params().Name != "P-256" {
			return nil, errors.New("issuer must use P-256")
		}
		der, e := x509.MarshalPKIXPublicKey(&cfg.SigningKey.PublicKey)
		if e != nil {
			return nil, e
		}
		expected := cfg.ExpectedPublicKey
		if expected == "" {
			expected = pro.PublicKey
		}
		if base64.StdEncoding.EncodeToString(der) != expected {
			return nil, errors.New("issuer key does not match application verifier")
		}
	}
	if e := os.MkdirAll(cfg.Dir, 0700); e != nil {
		return nil, e
	}
	lock, e := os.OpenFile(filepath.Join(cfg.Dir, "orders.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	if e = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		lock.Close()
		return nil, errors.New("another order service owns this directory")
	}
	s := &Service{file: filepath.Join(cfg.Dir, "orders.json"), lock: lock, state: diskState{Version: 1, Orders: map[string]Order{}}, enabled: cfg.Enabled, key: cfg.SigningKey, providers: map[string]Provider{}, now: time.Now, allowNasPurchases: cfg.AllowNasPurchases}
	for _, p := range cfg.Providers {
		if p == nil || s.providers[p.Name()] != nil {
			s.Close()
			return nil, ErrInvalid
		}
		s.providers[p.Name()] = p
	}
	b, e := os.ReadFile(s.file)
	if e != nil && !errors.Is(e, os.ErrNotExist) {
		s.Close()
		return nil, e
	}
	if e == nil {
		if len(b) > 32<<20 || json.Unmarshal(b, &s.state) != nil || s.state.Version != 1 || s.state.Orders == nil {
			s.Close()
			return nil, errors.New("damaged order data; original file retained")
		}
		for id, o := range s.state.Orders {
			if o.ID != id || !validAtom(id, 32, 32) || o.AmountMinor <= 0 || o.Currency != "CNY" || o.OwnerHash == "" {
				s.Close()
				return nil, errors.New("invalid persisted order; original file retained")
			}
		}
	}
	return s, nil
}
func (s *Service) Close() error {
	if s.lock == nil {
		return nil
	}
	syscall.Flock(int(s.lock.Fd()), syscall.LOCK_UN)
	e := s.lock.Close()
	s.lock = nil
	return e
}
func validAtom(v string, min, max int) bool {
	if len(v) < min || len(v) > max {
		return false
	}
	for _, c := range v {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}
func ownerHash(token string) string {
	v := sha256.Sum256([]byte(token))
	return hex.EncodeToString(v[:])
}
func authorized(o Order, token string) bool {
	return validAtom(token, 43, 128) && subtle.ConstantTimeCompare([]byte(o.OwnerHash), []byte(ownerHash(token))) == 1
}
func randomID() (string, error) {
	b := make([]byte, 16)
	_, e := rand.Read(b)
	return hex.EncodeToString(b), e
}
func (s *Service) persist(next diskState) error {
	if s.damaged {
		return errors.New("order storage unavailable")
	}
	b, e := json.Marshal(next)
	if e != nil {
		return e
	}
	tmp, e := os.CreateTemp(filepath.Dir(s.file), ".orders-*")
	if e != nil {
		return e
	}
	path := tmp.Name()
	defer os.Remove(path)
	if e = tmp.Chmod(0600); e == nil {
		_, e = tmp.Write(b)
	}
	if e == nil {
		e = tmp.Sync()
	}
	if closeErr := tmp.Close(); e == nil {
		e = closeErr
	}
	if e != nil {
		return e
	}
	if e = os.Rename(path, s.file); e != nil {
		return e
	}
	dir, e := os.Open(filepath.Dir(s.file))
	if e == nil {
		e = dir.Sync()
		dir.Close()
	}
	if e != nil {
		s.damaged = true
		return e
	}
	s.state = next
	return nil
}
func (s *Service) save(o Order) error {
	next := diskState{Version: 1, Orders: make(map[string]Order, len(s.state.Orders)+1)}
	for k, v := range s.state.Orders {
		next.Orders[k] = v
	}
	next.Orders[o.ID] = o
	return s.persist(next)
}

func (s *Service) Create(ctx context.Context, token, requestID, product, platform, channel string) (OrderView, error) {
	if !validAtom(token, 43, 128) || !validAtom(requestID, 16, 80) || product != pro.ProductID {
		return OrderView{}, ErrInvalid
	}
	if platform == "nas" && !s.allowNasPurchases {
		return OrderView{}, errors.New("platform_not_available")
	}
	if platform != "android" && !(platform == "nas" && s.allowNasPurchases) {
		return OrderView{}, errors.New("store_purchase_required")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.enabled || s.damaged {
		return OrderView{}, ErrClosed
	}
	provider := s.providers[channel]
	if provider == nil {
		return OrderView{}, ErrClosed
	}
	for _, o := range s.state.Orders {
		if o.OwnerHash == ownerHash(token) && o.RequestID == requestID {
			if o.ProductID != product || o.Channel != channel || o.Platform != platform {
				return OrderView{}, ErrConflict
			}
			return s.prepareLocked(ctx, provider, o)
		}
	}
	if len(s.state.Orders) >= 10000 {
		return OrderView{}, errors.New("order_capacity_reached")
	}
	id, e := randomID()
	if e != nil {
		return OrderView{}, e
	}
	at := s.now().Unix()
	o := Order{ID: id, ProductID: product, Platform: platform, Channel: channel, AmountMinor: pro.PriceMinor, Currency: "CNY", State: "CREATED", CreatedAt: at, ExpiresAt: at + 1800, UpdatedAt: at, Revision: 1, OwnerHash: ownerHash(token), RequestID: requestID}
	if e = s.save(o); e != nil {
		return OrderView{}, e
	}
	return s.prepareLocked(ctx, provider, o)
}
func (s *Service) prepareLocked(ctx context.Context, p Provider, o Order) (OrderView, error) {
	if o.State != "CREATED" {
		return view(o), nil
	}
	if s.now().Unix() > o.ExpiresAt {
		if o.Checkout.Value != "" {
			return OrderView{}, ErrPendingOrder
		}
		o.State = "EXPIRED"
		if e := s.save(o); e != nil {
			return OrderView{}, e
		}
		return view(o), nil
	}
	if o.Checkout.Value != "" {
		if wx, ok := p.(*Wechat); ok && o.Platform == "android" {
			checkout, e := wx.refreshAppCheckout(o.Checkout)
			if e != nil {
				return OrderView{}, e
			}
			o.Checkout = checkout
			if e = s.save(o); e != nil {
				return OrderView{}, e
			}
		}
		return view(o), nil
	}
	checkout, e := p.Prepare(ctx, o)
	if e != nil {
		return OrderView{}, errors.New("provider_temporarily_unavailable")
	}
	if checkout.Kind != "redirect" && checkout.Kind != "native_qr" && checkout.Kind != "alipay_app" && checkout.Kind != "wechat_app" {
		return OrderView{}, ErrInvalid
	}
	if len(checkout.Value) > 12000 || checkout.Value == "" {
		return OrderView{}, ErrInvalid
	}
	o.Checkout = checkout
	if e = s.save(o); e != nil {
		return OrderView{}, e
	}
	return view(o), nil
}
func (s *Service) Get(id, token string) (OrderView, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.damaged {
		return OrderView{}, ErrClosed
	}
	o, ok := s.state.Orders[id]
	if !ok || !authorized(o, token) {
		return OrderView{}, ErrNotFound
	}
	return view(o), nil
}
func (s *Service) Refresh(ctx context.Context, id, token string) (OrderView, error) {
	s.mu.Lock()
	o, ok := s.state.Orders[id]
	if !ok || !authorized(o, token) {
		s.mu.Unlock()
		return OrderView{}, ErrNotFound
	}
	p := s.providers[o.Channel]
	enabled := s.key != nil && !s.damaged
	s.mu.Unlock()
	if !enabled || p == nil {
		return OrderView{}, ErrClosed
	}
	receipt, e := p.Query(ctx, o)
	if e != nil {
		return OrderView{}, errors.New("provider_temporarily_unavailable")
	}
	if e = s.settle(o.Channel, receipt); e != nil {
		return OrderView{}, e
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	current, ok := s.state.Orders[id]
	if !ok || !authorized(current, token) {
		return OrderView{}, ErrNotFound
	}
	return s.prepareLocked(ctx, p, current)
}

// Unexported: HTTP callers never supply trusted receipts. Adapters must verify
// the provider's cryptographic signature and merchant/app identity first.
func (s *Service) settle(channel string, r Receipt) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.key == nil || s.damaged {
		return ErrClosed
	}
	o, ok := s.state.Orders[r.OrderID]
	if !ok {
		return ErrNotFound
	}
	if o.Channel != channel || r.Currency != o.Currency || r.AmountMinor != o.AmountMinor {
		return ErrConflict
	}
	if r.State == "PENDING" {
		return nil
	}
	if r.State == "CLOSED" && r.TradeID == "" && (o.State == "CREATED" || o.State == "EXPIRED" || o.State == "CLOSED") && o.TradeID == "" {
		if o.State == "CLOSED" {
			return nil
		}
		o.State = "CLOSED"
		if o.Revision < 1 {
			o.Revision = 1
		}
		if o.Revision < 1 {
			o.Revision = 1
		}
		o.Revision++
		o.UpdatedAt = s.now().Unix()
		return s.save(o)
	}
	if r.TradeID == "" || len(r.TradeID) > 128 {
		return ErrConflict
	}
	if o.TradeID != "" && o.TradeID != r.TradeID {
		return ErrConflict
	}
	for _, other := range s.state.Orders {
		if other.ID != o.ID && other.Channel == channel && other.TradeID == r.TradeID {
			return ErrConflict
		}
	}
	if r.State == "REFUND" {
		return s.refundLocked(o, r)
	}
	switch r.State {
	case "PAID":
		if o.State == "REFUNDED" || o.State == "CLOSED" {
			return ErrConflict
		}
		if o.State == "PAID" {
			return nil
		}
		code := ""
		if o.Platform != "android" {
			var e error
			code, e = issue(s.key, o.ID)
			if e != nil {
				return e
			}
		}
		o.State = "PAID"
		o.License = code
		o.TradeID = r.TradeID
	case "CLOSED":
		if o.State == "REFUNDED" || o.State == "CLOSED" {
			return nil
		}
		if o.State == "PAID" {
			o.State = "REFUNDED"
		} else {
			o.State = "CLOSED"
		}
		o.TradeID = r.TradeID
	case "PENDING":
		return nil
	default:
		return ErrInvalid
	}
	if o.Revision < 1 {
		o.Revision = 1
	}
	o.Revision++
	o.UpdatedAt = s.now().Unix()
	return s.save(o)
}
func issue(key *ecdsa.PrivateKey, id string) (string, error) {
	if key == nil {
		return "", ErrClosed
	}
	b, e := json.Marshal(map[string]any{"id": "order-" + id, "holder": "Pro 用户", "product": "guizhang-pro", "permanent": true})
	if e != nil {
		return "", e
	}
	h := sha256.Sum256(b)
	signature, e := ecdsa.SignASN1(rand.Reader, key, h[:])
	if e != nil {
		return "", e
	}
	return fmt.Sprintf("GZ1.%s.%s", base64.RawURLEncoding.EncodeToString(b), base64.RawURLEncoding.EncodeToString(signature)), nil
}
func Bearer(value string) string {
	if !strings.HasPrefix(value, "Bearer ") {
		return ""
	}
	return strings.TrimPrefix(value, "Bearer ")
}
