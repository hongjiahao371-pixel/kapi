// Package store is the authoritative server state: an append-only log plus an
// in-memory index rebuilt by replaying it.
//
// Why not SQLite: this server does exactly four things with persisted data —
// append a change, read a row, read a tail of the log, and test whether a
// change_id has been seen. All four are O(1) or O(n) over a slice. A personal
// ledger is tens of thousands of rows, which is a few megabytes in memory. A
// database engine would add a CGO or a 30MB pure-Go dependency, a schema
// migration story, and a corrupt-file recovery story, in exchange for nothing.
//
// The log stores MERGED SNAPSHOTS, not raw changes. That makes replay
// trivial (last snapshot per row wins), makes /changes return exactly what a
// client needs, and means a restart never has to re-run merge logic.
//
// Durability: every Push is appended as JSON lines and fsynced BEFORE the
// in-memory index is updated. The log is therefore always at least as advanced
// as memory, so a crash can lose in-memory state but never committed state.
// A client that retries after a crash is de-duplicated by change_id, which is
// itself rebuilt from the log.
package store

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"kapi/sync/internal/merge"
)

// PairCodeTTL is how long a pairing code stays valid.
const PairCodeTTL = 10 * time.Minute

// maxLogWarnBytes is where replay starts complaining about an uncompacted log.
const maxLogWarnBytes = 512 << 20

var (
	ErrPairCodeInvalid = errors.New("pair code is invalid or expired")
	ErrUnauthorized    = errors.New("invalid token")
	ErrBadSince        = errors.New("since must be >= 0")
	ErrLimitOutOfRange = errors.New("limit must be between 1 and 1000")
	ErrEmptyPush       = errors.New("no changes supplied")
	ErrLogCorrupt      = errors.New("log file is corrupt beyond the final record")
)

// LogEntry is one line of the append-only log.
type LogEntry struct {
	Seq       int64  `json:"seq"`
	Entity    string `json:"entity"`
	EntityID  string `json:"entity_id"`
	Op        string `json:"op"`
	ChangeID  string `json:"change_id"`
	Origin    string `json:"origin"`
	CreatedAt int64  `json:"created_at"`
	// Snapshot is the fully merged row AFTER this change was applied.
	Snapshot *merge.Record `json:"snapshot"`
}

// Device is a paired client.
//
// TokenHash is serialised. That is safe because Device is only ever written to
// meta.json — the HTTP layer hands clients a PairResult, never a Device — and
// necessary because a hash that is not persisted is a hash that does not
// survive a restart, which silently signs every paired phone out of its own
// data the first time the container bounces.
type Device struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	TokenHash  string `json:"token_hash"`
	PairedAt   int64  `json:"paired_at"`
	LastSeenAt int64  `json:"last_seen_at"`
}

// RejectedChange tells a client that one of its pushes lost a comparison, so
// the client can warn the user instead of silently losing an edit.
type RejectedChange struct {
	ChangeID string `json:"change_id"`
	Entity   string `json:"entity"`
	EntityID string `json:"entity_id"`
	Reason   string `json:"reason"`
	// Fields the push lost, for field-level LWW losses.
	Fields []string `json:"fields,omitempty"`
	// Kinds: "stale", "delete_rejected".
	Kind string `json:"kind"`
}

// PushResult is the response to POST /v1/push.
type PushResult struct {
	Applied  []string         `json:"applied"`
	Rejected []RejectedChange `json:"rejected"`
	Cursor   int64            `json:"cursor"`
}

// Health is the response to GET /v1/health. Its real job is to be the first
// thing the Android pairing wizard calls, so a user stuck on "连不上" can be
// told which of the five steps failed.
type Health struct {
	OK          bool   `json:"ok"`
	ServerVer   string `json:"server_version"`
	DBSeq       int64  `json:"db_seq"`
	DeviceCount int    `json:"device_count"`
	Rows        int    `json:"rows"`
	PairCode    string `json:"pair_code,omitempty"`
}

// Store is safe for concurrent use.
type Store struct {
	mu  sync.RWMutex
	dir string

	seq  int64
	rows map[string]*merge.Record // key: entity + "\x00" + entityID
	log  []LogEntry
	seen map[string]struct{}

	devices map[string]*Device
	byHash  map[string]*Device

	pairCode string
	pairExp  time.Time

	logFile *os.File
	logSize int64

	now func() time.Time // injectable for tests
}

func key(entity, entityID string) string { return entity + "\x00" + entityID }

// Open loads (or creates) a store rooted at dir.
func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	s := &Store{
		dir:     dir,
		rows:    map[string]*merge.Record{},
		seen:    map[string]struct{}{},
		devices: map[string]*Device{},
		byHash:  map[string]*Device{},
		now:     time.Now,
	}
	if err := s.replay(); err != nil {
		return nil, err
	}

	f, err := os.OpenFile(filepath.Join(dir, "log.jsonl"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return nil, err
	}
	s.logFile = f
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	s.logSize = info.Size()

	if _, err := s.rotatePairCode(); err != nil {
		f.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.logFile == nil {
		return nil
	}
	err := s.logFile.Close()
	s.logFile = nil
	return err
}

// replay rebuilds in-memory state from the log.
//
// Torn-write vs. corruption, precisely:
//
// A crash during append leaves a partial final line with NO trailing newline,
// because the newline is part of the very write that got cut short. That is
// the only shape a torn write can take, and it is distinguishable from real
// damage: a file that ends in a newline had every write complete, so an
// unparseable line inside it is corruption and must be reported, not hidden.
//
// The earlier version of this function treated ANY unparseable final line as
// torn, which meant a genuinely corrupt log was silently truncated on every
// start — losing every change after the bad line, with no error anywhere.
//
// Note: the log is read whole. For a personal ledger that is a few MB, and
// compaction is a v2 problem. A guard warns rather than silently ballooning.
func (s *Store) replay() error {
	path := filepath.Join(s.dir, "log.jsonl")
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}

	if len(data) > maxLogWarnBytes {
		log.Printf("kapi-sync: 日志已达 %d 字节,建议做一次压实(尚未实现)", len(data))
	}

	if len(data) > 0 && data[len(data)-1] != '\n' {
		cut := bytes.LastIndexByte(data, '\n')
		if cut < 0 {
			data = nil
		} else {
			data = data[:cut+1]
		}
		if err := os.Truncate(path, int64(len(data))); err != nil {
			return err
		}
		s.logSize = int64(len(data))
	} else {
		s.logSize = int64(len(data))
	}

	for lineNo, line := range bytes.Split(data, []byte("\n")) {
		if len(bytes.TrimSpace(line)) == 0 {
			continue
		}
		var e LogEntry
		if err := json.Unmarshal(line, &e); err != nil {
			return fmt.Errorf("%w: line %d: %v", ErrLogCorrupt, lineNo+1, err)
		}
		if e.Snapshot != nil {
			s.rows[key(e.Entity, e.EntityID)] = e.Snapshot
		}
		if e.ChangeID != "" {
			s.seen[e.ChangeID] = struct{}{}
		}
		if e.Seq > s.seq {
			s.seq = e.Seq
		}
		s.log = append(s.log, e)
	}

	return s.replayMeta()
}

func (s *Store) replayMeta() error {
	b, err := os.ReadFile(filepath.Join(s.dir, "meta.json"))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var m struct {
		Devices  []*Device `json:"devices"`
		PairCode string    `json:"pair_code"`
		PairExp  int64     `json:"pair_exp"`
	}
	if err := json.Unmarshal(b, &m); err != nil {
		return err
	}
	for _, d := range m.Devices {
		s.devices[d.ID] = d
		s.byHash[d.TokenHash] = d
	}
	s.pairCode = m.PairCode
	if m.PairExp > 0 {
		s.pairExp = time.UnixMilli(m.PairExp)
	}
	return nil
}

func (s *Store) saveMeta() error {
	m := struct {
		Devices  []*Device `json:"devices"`
		PairCode string    `json:"pair_code"`
		PairExp  int64     `json:"pair_exp"`
	}{
		Devices:  mapValues(s.devices),
		PairCode: s.pairCode,
		PairExp:  s.pairExp.UnixMilli(),
	}
	b, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	tmp := filepath.Join(s.dir, "meta.json.tmp")
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(s.dir, "meta.json"))
}

func mapValues(m map[string]*Device) []*Device {
	out := make([]*Device, 0, len(m))
	for _, d := range m {
		out = append(out, d)
	}
	return out
}

// ---------------------------------------------------------------------------
// pairing and auth
// ---------------------------------------------------------------------------

// PairCode returns the current pairing code, rotating it if expired.
func (s *Store) PairCode() (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.pairCode == "" || s.now().After(s.pairExp) {
		return s.rotatePairCode()
	}
	return s.pairCode, nil
}

func (s *Store) rotatePairCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	s.pairCode = fmt.Sprintf("%06d", n.Int64())
	s.pairExp = s.now().Add(PairCodeTTL)
	if err := s.saveMeta(); err != nil {
		return "", err
	}
	return s.pairCode, nil
}

// PairResult is what the client gets back. Token is returned exactly once.
type PairResult struct {
	DeviceID string `json:"device_id"`
	Token    string `json:"token"`
	Cursor   int64  `json:"cursor"`
}

// Pair exchanges a pairing code for a long-lived device token.
func (s *Store) Pair(code, deviceName string) (*PairResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	code = strings.TrimSpace(code)
	if s.pairCode == "" || s.now().After(s.pairExp) || code != s.pairCode {
		return nil, ErrPairCodeInvalid
	}

	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return nil, err
	}
	token := hex.EncodeToString(raw)
	sum := sha256.Sum256([]byte(token))
	id := "dev_" + hex.EncodeToString(sum[:4])

	d := &Device{ID: id, Name: deviceName, TokenHash: hex.EncodeToString(sum[:]), PairedAt: s.now().UnixMilli(), LastSeenAt: s.now().UnixMilli()}
	s.devices[id] = d
	s.byHash[d.TokenHash] = d
	if err := s.saveMeta(); err != nil {
		return nil, err
	}
	return &PairResult{DeviceID: id, Token: token, Cursor: s.seq}, nil
}

// Authenticate resolves a bearer token to a device and bumps last-seen.
func (s *Store) Authenticate(token string) (*Device, error) {
	sum := sha256.Sum256([]byte(strings.TrimSpace(token)))
	d, ok := s.byHash[hex.EncodeToString(sum[:])]
	if !ok {
		return nil, ErrUnauthorized
	}
	s.mu.Lock()
	d.LastSeenAt = s.now().UnixMilli()
	s.mu.Unlock()
	return d, nil
}

// ---------------------------------------------------------------------------
// sync
// ---------------------------------------------------------------------------

// Changes returns log entries after `since`, up to limit.
func (s *Store) Changes(since int64, limit int) ([]LogEntry, int64, bool, error) {
	if since < 0 {
		return nil, 0, false, ErrBadSince
	}
	if limit < 1 || limit > 1000 {
		return nil, 0, false, ErrLimitOutOfRange
	}
	s.mu.RLock()
	defer s.mu.RUnlock()

	out := make([]LogEntry, 0, limit)
	cursor := since
	hasMore := false
	for _, e := range s.log {
		if e.Seq <= since {
			continue
		}
		if len(out) == limit {
			hasMore = true
			break
		}
		out = append(out, e)
		cursor = e.Seq
	}
	return out, cursor, hasMore, nil
}

// Push applies a batch of changes.
//
// Ordering note: the whole batch is merged against working copies, serialised,
// and written in ONE append+fsync, and only then committed to memory. A crash
// before the commit leaves the log ahead of memory, which replay resolves in
// the client's favour — the client retries, change_ids de-duplicate, nothing is
// lost and nothing is applied twice.
func (s *Store) Push(deviceID string, changes []merge.Change) (*PushResult, error) {
	if len(changes) == 0 {
		return nil, ErrEmptyPush
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	working := map[string]*merge.Record{}
	entries := make([]LogEntry, 0, len(changes))
	res := &PushResult{Applied: []string{}, Rejected: []RejectedChange{}}
	seq := s.seq
	now := s.now().UnixMilli()

	for _, ch := range changes {
		if err := ch.Validate(); err != nil {
			res.Rejected = append(res.Rejected, RejectedChange{
				ChangeID: ch.ChangeID, Entity: ch.Entity, EntityID: ch.EntityID,
				Reason: err.Error(), Kind: "invalid",
			})
			continue
		}
		if _, dup := s.seen[ch.ChangeID]; dup {
			res.Applied = append(res.Applied, ch.ChangeID)
			continue
		}

		k := key(ch.Entity, ch.EntityID)
		cur, ok := working[k]
		if !ok {
			cur = s.rows[k]
		}

		merged, mres := merge.Apply(cur, ch)

		seq++
		entries = append(entries, LogEntry{
			Seq: seq, Entity: ch.Entity, EntityID: ch.EntityID, Op: ch.Op,
			ChangeID: ch.ChangeID, Origin: ch.Origin, CreatedAt: now, Snapshot: merged,
		})
		working[k] = merged
		s.seen[ch.ChangeID] = struct{}{}
		res.Applied = append(res.Applied, ch.ChangeID)

		if len(mres.RejectedFields) > 0 {
			res.Rejected = append(res.Rejected, RejectedChange{
				ChangeID: ch.ChangeID, Entity: ch.Entity, EntityID: ch.EntityID,
				Reason: "another device changed this row more recently",
				Fields: mres.RejectedFields, Kind: "stale",
			})
		}
		if mres.DeleteRejected {
			res.Rejected = append(res.Rejected, RejectedChange{
				ChangeID: ch.ChangeID, Entity: ch.Entity, EntityID: ch.EntityID,
				Reason: "this row was edited after it was deleted; the edit wins",
				Kind:   "delete_rejected",
			})
		}
	}

	if len(entries) > 0 {
		if err := s.appendEntries(entries); err != nil {
			// Nothing committed: roll the in-memory bookkeeping back so a
			// retry sees a clean slate.
			for _, e := range entries {
				delete(s.seen, e.ChangeID)
			}
			return nil, err
		}
		for _, e := range entries {
			s.rows[key(e.Entity, e.EntityID)] = e.Snapshot
			s.log = append(s.log, e)
		}
		s.seq = seq
	}

	res.Cursor = s.seq
	return res, nil
}

func (s *Store) appendEntries(entries []LogEntry) error {
	var buf strings.Builder
	for _, e := range entries {
		b, err := json.Marshal(e)
		if err != nil {
			return err
		}
		buf.Write(b)
		buf.WriteByte('\n')
	}
	if _, err := s.logFile.WriteString(buf.String()); err != nil {
		return err
	}
	if err := s.logFile.Sync(); err != nil {
		return err
	}
	s.logSize += int64(buf.Len())
	return nil
}

// Row returns the current state of one row, for the admin/debug endpoint.
func (s *Store) Row(entity, entityID string) (*merge.Record, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	r, ok := s.rows[key(entity, entityID)]
	return r, ok
}

// HealthInfo reports server state for the pairing wizard.
func (s *Store) HealthInfo() Health {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return Health{
		OK:          true,
		ServerVer:   Version,
		DBSeq:       s.seq,
		DeviceCount: len(s.devices),
		Rows:        len(s.rows),
	}
}

// Version is stamped at build time.
var Version = "0.1.0-dev"
