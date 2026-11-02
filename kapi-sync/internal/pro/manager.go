package pro

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

type stored struct {
	Version    int               `json:"version"`
	LegacyGift bool              `json:"legacy_gift"`
	License    string            `json:"license,omitempty"`
	Orders     map[string]string `json:"orders,omitempty"`
}
type Status struct {
	Version               int               `json:"policy_version"`
	Enabled               bool              `json:"enabled"`
	Source                string            `json:"source"`
	PaymentsEnabled       bool              `json:"payments_enabled"`
	Free                  map[string]string `json:"free"`
	Premium               map[string]string `json:"pro"`
	Catalogue             Catalogue         `json:"catalogue"`
	OrderRestoreAvailable bool              `json:"order_restore_available"`
}
type Manager struct {
	mu        sync.RWMutex
	file      string
	state     stored
	loadIssue bool
}

func Open(dir string, hadData bool) (*Manager, error) {
	m := &Manager{file: filepath.Join(dir, "entitlements.json")}
	b, e := os.ReadFile(m.file)
	if errors.Is(e, os.ErrNotExist) {
		m.state = stored{Version: PolicyVersion, LegacyGift: hadData}
		if e = m.save(m.state); e != nil {
			return nil, e
		}
	} else if e != nil {
		return nil, e
	} else if json.Unmarshal(b, &m.state) != nil || m.state.Version != PolicyVersion {
		m.state = stored{Version: PolicyVersion}
		m.loadIssue = true
	}
	return m, nil
}
func (m *Manager) enabledLocked() bool {
	if m.state.LegacyGift {
		return true
	}
	if _, e := Verify(m.state.License, PublicKey); e == nil {
		return true
	}
	for id, proof := range m.state.Orders {
		if value, e := verifyOrderProof(proof, id, "", PublicKey); e == nil && value.State == "PAID" {
			return true
		}
	}
	return false
}
func (m *Manager) Enabled() bool              { m.mu.RLock(); defer m.mu.RUnlock(); return m.enabledLocked() }
func (m *Manager) Allows(feature string) bool { return Allows(feature, m.Enabled()) }
func (m *Manager) Status() Status {
	m.mu.RLock()
	defer m.mu.RUnlock()
	source := "免费版"
	if m.loadIssue {
		source = "权益待恢复（基础数据功能可用）"
	}
	enabled := m.enabledLocked()
	if m.state.LegacyGift {
		source = "内测永久赠送"
	} else if enabled {
		source = "永久 Pro"
	}
	return Status{Version: PolicyVersion, Enabled: enabled, Source: source, PaymentsEnabled: false, Free: Free, Premium: Premium, Catalogue: ProductCatalogue()}
}
func (m *Manager) Activate(code string) error {
	if _, e := Verify(code, PublicKey); e != nil {
		return e
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	next := m.state
	next.License = strings.TrimSpace(code)
	if e := m.save(next); e != nil {
		return e
	}
	m.state = next
	m.loadIssue = false
	return nil
}
func (m *Manager) save(value stored) error {
	b, e := json.Marshal(value)
	if e != nil {
		return e
	}
	f, e := os.OpenFile(m.file+".tmp", os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if e != nil {
		return e
	}
	if _, e = f.Write(b); e == nil {
		e = f.Sync()
	}
	if c := f.Close(); e == nil {
		e = c
	}
	if e != nil {
		return e
	}
	if e = os.Rename(m.file+".tmp", m.file); e != nil {
		return e
	}
	d, e := os.Open(filepath.Dir(m.file))
	if e != nil {
		return e
	}
	defer d.Close()
	return d.Sync()
}
