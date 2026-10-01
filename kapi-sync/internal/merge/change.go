package merge

import "encoding/json"

// Op is the kind of change a client is submitting.
const (
	OpUpsert = "upsert"
	OpDelete = "delete"
)

// V marshals a Go value into a field value.
//
// Clients on the wire can send plain JSON; this exists so tests and
// server-side code can build values without hand-writing byte slices. It panics
// on an unmarshalable value, which is a programming error, not a runtime one.
func V(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		panic("merge: unmarshalable field value: " + err.Error())
	}
	return b
}

// Change is a single row-level mutation submitted by a client.
//
// It is idempotent by ChangeID at the store layer, and order-independent at
// the merge layer: applying the same set of changes in any permutation
// converges to the same state (see TestConvergenceAcrossPermutations).
type Change struct {
	ChangeID string                     `json:"change_id"`
	Entity   string                     `json:"entity"`
	EntityID string                     `json:"entity_id"`
	Op       string                     `json:"op"`
	Data     map[string]json.RawMessage `json:"data"`
	FV       FieldVersions              `json:"fv"`
	Lamport  int64                      `json:"lamport"`

	// Origin is the submitting device id. It is the tiebreak in every LWW
	// comparison, so it must be set even when per-field stamps are supplied.
	Origin string `json:"origin"`
}

// Stamp returns the row-level fallback stamp, used for any field that arrives
// without its own per-field version.
//
// This is deliberate leniency: a client that has not implemented field-level
// stamps still syncs correctly, just with row-level granularity (one device
// editing any field clobbers the whole row). Newer clients get the real thing.
func (c Change) Stamp() FVEntry {
	return FVEntry{L: c.Lamport, D: c.Origin}
}

// stampFor returns the per-field stamp, falling back to the row-level stamp.
func (fv FieldVersions) stampFor(field string, fallback FVEntry) FVEntry {
	if e, ok := fv[field]; ok {
		return e
	}
	return fallback
}

// Validate performs the structural checks the server must reject outright.
// Malformed changes are not "conflicts" — they are client bugs and are
// surfaced as errors rather than silently dropped.
func (c Change) Validate() error {
	switch {
	case c.ChangeID == "":
		return ErrMissingChangeID
	case c.Entity == "":
		return ErrMissingEntity
	case c.EntityID == "":
		return ErrMissingEntityID
	case c.Origin == "":
		return ErrMissingOrigin
	case c.Op != OpUpsert && c.Op != OpDelete:
		return ErrBadOp
	case c.Op == OpUpsert && c.Lamport <= 0:
		// A delete carries its stamp in Lamport too, but tolerates 0 because
		// a delete of a row the client only knows about locally is still valid.
		return ErrMissingLamport
	}
	return nil
}

type ValidationError string

func (e ValidationError) Error() string { return string(e) }

const (
	ErrMissingChangeID = ValidationError("change_id is required")
	ErrMissingEntity   = ValidationError("entity is required")
	ErrMissingEntityID = ValidationError("entity_id is required")
	ErrMissingOrigin   = ValidationError("origin (device id) is required")
	ErrMissingLamport  = ValidationError("lamport must be > 0 for upsert")
	ErrBadOp           = ValidationError("op must be upsert or delete")
)
