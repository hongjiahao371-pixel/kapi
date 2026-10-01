// Package merge implements the row-level conflict resolution used by the
// kapi-sync server.
//
// Design contract (see DRAFT-tech-spec.md §5):
//
//   - We never trust a device's wall clock. `occurred_at` is user-editable and
//     device clocks drift, so "last writer wins by timestamp" silently corrupts
//     merged rows with no error surfaced. We use a Lamport counter that is
//     monotonic per device, with device_id as a tiebreak, giving a total order
//     that is independent of arrival order.
//   - Merging is FIELD-level, not row-level. If device A edited the amount and
//     device B only added a note, neither must clobber the other.
//   - The tombstone is NOT stored as a boolean that gets flipped. A row is
//     deleted if and only if the delete stamp strictly beats the newest content
//     stamp. This makes the deleted flag DERIVED rather than accumulated, so
//     it cannot drift, and it makes "edit after delete" resurrect and "delete
//     after edit" kill fall out of one rule instead of two.
//
// That last point was found by TestConvergenceAcrossPermutations, not by
// reading the code: an earlier version advanced DelFV only when a delete won,
// so a row edited at lamport 33 still carried a tombstone stamp of 16 and a
// later delete at 30 would kill it — while the reverse arrival order
// resurrected it. Same changes, two different answers.
//
// This package is pure: no I/O, no clock reads, no database. Every result is a
// pure function of its inputs, which is what makes the adversarial tests in
// apply_test.go meaningful.
package merge

import (
	"encoding/json"
	"sort"
)

// FVEntry is a field-level version stamp.
//
//	L is a Lamport counter (monotonic per device).
//	D is the originating device id, used only to break L ties so that the merge
//	  result does not depend on the order changes happen to arrive in.
type FVEntry struct {
	L int64  `json:"l"`
	D string `json:"d"`
}

// Compare reports which stamp is newer.
//
//	>0  a is newer
//	<0  b is newer
//	 0  a and b are identical
func (a FVEntry) Compare(b FVEntry) int {
	if a.L != b.L {
		if a.L > b.L {
			return 1
		}
		return -1
	}
	if a.D > b.D {
		return 1
	}
	if a.D < b.D {
		return -1
	}
	return 0
}

// FieldVersions maps a field name to its winning stamp.
type FieldVersions map[string]FVEntry

// Record is the authoritative state of one entity row.
//
// Values holds business fields as raw JSON. The server never interprets a
// value — it merges stamps and ships bytes — so keeping them as json.RawMessage
// is what makes a row round-trip byte-identically across a restart.
//
// This is not a micro-optimisation. Decoding into map[string]any turns every
// number into float64, so a row's amount_minor would be int before a restart
// and float64 after it. That is a silent type change on persisted data, and
// for money it is exactly the float contamination the schema forbids.
type Record struct {
	Entity   string                     `json:"entity"`
	EntityID string                     `json:"entity_id"`
	Values   map[string]json.RawMessage `json:"values"`
	FV       FieldVersions              `json:"fv"`

	// DelFV is the stamp of the delete operation, if one has ever been seen.
	// The zero value means "never deleted". It is a historical fact about the
	// operation, not a mutable pointer to current state.
	DelFV FVEntry `json:"del_fv"`

	// Deleted is derived: DelFV strictly beats the newest content stamp.
	Deleted bool `json:"deleted"`

	// Lamport is the max stamp across the row. Clients use it for stable
	// ordering; the server does not need it for cursor sequencing.
	Lamport int64 `json:"lamport"`
}

// Clone returns a copy the caller may mutate without affecting the original.
func (r *Record) Clone() *Record {
	c := &Record{
		Entity:   r.Entity,
		EntityID: r.EntityID,
		Values:   make(map[string]json.RawMessage, len(r.Values)),
		FV:       make(FieldVersions, len(r.FV)),
		DelFV:    r.DelFV,
		Deleted:  r.Deleted,
		Lamport:  r.Lamport,
	}
	for k, v := range r.Values {
		c.Values[k] = v
	}
	for k, v := range r.FV {
		c.FV[k] = v
	}
	return c
}

// Result describes what Apply did. It exists so the server can return
// conflicts to the client and so tests can assert on behaviour rather than
// only on final state.
type Result struct {
	// Changed is true when the resulting record differs from the input record.
	Changed bool

	// AcceptedFields lists fields whose value was taken from this push.
	AcceptedFields []string

	// RejectedFields lists fields where this push lost the LWW comparison.
	// Surfaced to the client so a silently-dropped amount is never invisible.
	RejectedFields []string

	// Resurrected is true when a delete was overruled by a strictly newer edit.
	Resurrected bool

	// Killed is true when a live row became deleted.
	Killed bool

	// DeleteRejected is true when an incoming delete lost to existing content,
	// i.e. the row was edited after it was deleted and the edit wins.
	DeleteRejected bool
}

// Apply merges a change into a row and returns the new authoritative state.
//
// cur is the current server state, or nil if the row does not exist yet.
// The returned record is always a fresh copy; cur is never mutated.
//
// A change carries a row-level Lamport (Change.Lamport) as a fallback for any
// field that arrives without its own stamp, so clients that have not yet
// implemented per-field stamps still sync correctly — just with row-level
// rather than field-level granularity.
func Apply(cur *Record, ch Change) (*Record, Result) {
	incoming := ch.Stamp()
	var res Result

	wasDeleted := false
	if cur != nil {
		wasDeleted = cur.Deleted
	}

	var rec *Record
	if cur == nil {
		rec = &Record{
			Entity:   ch.Entity,
			EntityID: ch.EntityID,
			Values:   make(map[string]json.RawMessage, len(ch.Data)),
			FV:       make(FieldVersions, len(ch.Data)),
		}
		if ch.Op == OpDelete {
			// A delete can legitimately be the first thing we ever hear about
			// a row — the device created it offline and deleted it before it
			// ever synced. The tombstone is the only thing we learn.
			rec.DelFV = incoming
		}
	} else {
		rec = cur.Clone()
	}

	// Record a newer delete. Whether the row actually becomes deleted is
	// decided by recomputeState, because content may already be newer.
	if ch.Op == OpDelete && incoming.Compare(rec.DelFV) > 0 {
		rec.DelFV = incoming
	}

	// Field-level LWW.
	for k, v := range ch.Data {
		in := ch.FV.stampFor(k, incoming)
		curStamp, known := rec.FV[k]

		if !known || in.Compare(curStamp) > 0 {
			rec.Values[k] = v
			rec.FV[k] = in
			res.AcceptedFields = append(res.AcceptedFields, k)
		} else {
			res.RejectedFields = append(res.RejectedFields, k)
		}
	}

	rec.recomputeState()

	if ch.Op == OpDelete && !rec.Deleted {
		res.DeleteRejected = true
	}
	res.Killed = !wasDeleted && rec.Deleted
	res.Resurrected = wasDeleted && !rec.Deleted

	res.Changed = res.Killed || res.Resurrected || len(res.AcceptedFields) > 0
	sort.Strings(res.AcceptedFields)
	sort.Strings(res.RejectedFields)
	return rec, res
}

// recomputeState derives Deleted and Lamport from the stamps.
//
// A row is deleted exactly when the delete stamp strictly beats the newest
// content stamp. A tie is broken by device id, matching Compare, so the result
// is a total order and does not depend on arrival order.
func (r *Record) recomputeState() {
	newest := FVEntry{} // zero: below any real stamp
	maxL := r.DelFV.L
	for _, e := range r.FV {
		if e.Compare(newest) > 0 {
			newest = e
		}
		if e.L > maxL {
			maxL = e.L
		}
	}
	r.Deleted = r.DelFV.Compare(newest) > 0
	r.Lamport = maxL
}
