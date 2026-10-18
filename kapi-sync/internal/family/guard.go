package family

import (
	"encoding/json"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
)

type writePolicy struct {
	devices map[string]string
	active  map[string]bool
	names   map[string]string
}

func (h *Hub) publishPolicyLocked() {
	p := writePolicy{devices: map[string]string{}, active: map[string]bool{}, names: map[string]string{}}
	for id, member := range h.state.Members {
		p.active[id] = member.Active
		p.names[id] = member.Name
	}
	for device, member := range h.state.SharedDevices {
		p.devices[device] = member
	}
	p.devices["family-owner-admin"] = "owner"
	h.policy.Store(p)
}
func recordString(raw json.RawMessage) string {
	for i := 0; i < 3; i++ {
		var value string
		if json.Unmarshal(raw, &value) != nil {
			return ""
		}
		raw = []byte(value)
		if len(value) == 0 || value[0] != '"' {
			return value
		}
	}
	return ""
}
func familyValue(value string) json.RawMessage { return merge.V(string(merge.V(value))) }
func (h *Hub) guardSharedWrite(device string, cur *merge.Record, ch merge.Change) (merge.Change, error) {
	if ch.Entity != "txn" {
		return ch, nil
	}
	for key := range ch.FV {
		if key == "family_created_by" || key == "family_created_name" || key == "family_updated_by" {
			return ch, store.ErrWriteForbidden
		}
	}
	for key := range ch.Data {
		if key == "family_created_by" || key == "family_created_name" || key == "family_updated_by" {
			return ch, store.ErrWriteForbidden
		}
	}
	p := h.policy.Load().(writePolicy)
	member := p.devices[device]
	if member == "" || !p.active[member] {
		return ch, store.ErrWriteForbidden
	}
	creator := ""
	if cur != nil {
		creator = recordString(cur.Values["family_created_by"])
	}
	// Membership management never grants ownership of another person's bills.
	// Missing attribution also fails closed, including for the NAS owner.
	if cur != nil && creator != member {
		return ch, store.ErrWriteForbidden
	}
	data := make(map[string]json.RawMessage, len(ch.Data)+2)
	for k, v := range ch.Data {
		data[k] = v
	}
	fv := make(merge.FieldVersions, len(ch.FV)+2)
	for k, v := range ch.FV {
		fv[k] = v
	}
	// The authenticated device, not request fields, supplies attribution.
	ch.Origin = device
	delete(data, "family_created_by")
	delete(fv, "family_created_by")
	delete(data, "family_updated_by")
	delete(fv, "family_updated_by")
	if cur == nil {
		data["family_created_by"] = familyValue(member)
		data["family_created_name"] = familyValue(p.names[member])
		if ch.Op == merge.OpDelete {
			// Keep attribution strictly older than the first deletion.
			stamp := merge.FVEntry{L: ch.Lamport - 1, D: device}
			if stamp.L < 0 {
				return ch, store.ErrWriteForbidden
			}
			fv["family_created_by"] = stamp
			fv["family_created_name"] = stamp
		} else {
			fv["family_created_by"] = ch.Stamp()
			fv["family_created_name"] = ch.Stamp()
		}
	}
	if ch.Op != merge.OpDelete {
		data["family_updated_by"] = familyValue(member)
		fv["family_updated_by"] = ch.Stamp()
	}
	ch.Data = data
	ch.FV = fv
	return ch, nil
}
