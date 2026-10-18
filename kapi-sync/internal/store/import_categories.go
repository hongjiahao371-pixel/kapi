package store

import (
	"kapi/sync/internal/billimport"
	"sort"
	"strings"
)

func ImportCategoryKey(row billimport.Row) string {
	if row.Category == nil || strings.TrimSpace(*row.Category) == "" {
		return ""
	}
	return row.Source + "\x1f" + row.Direction + "\x1f" + strings.TrimSpace(*row.Category)
}
func (s *Store) ImportCategoryMappings(ledger string) map[string]string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := map[string]string{}
	for k, v := range s.importMappings[ledger] {
		out[k] = v
	}
	return out
}
func (s *Store) rememberImportMappingsLocked(ledger string, learned map[string]map[string]bool) (int, string) {
	if len(learned) == 0 {
		return 0, ""
	}
	next := map[string]string{}
	old := s.importMappings[ledger]
	for k, v := range old {
		next[k] = v
	}
	n := 0
	for k, ids := range learned {
		if len(ids) == 1 {
			for id := range ids {
				next[k] = id
				n++
			}
		} else {
			delete(next, k)
		}
	}
	keys := []string{}
	for k := range next {
		if learned[k] == nil {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	for _, k := range keys {
		if len(next) <= 2000 {
			break
		}
		delete(next, k)
	}
	if len(next) > 2000 {
		keys = nil
		for k := range next {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			if len(next) <= 2000 {
				break
			}
			delete(next, k)
		}
	}
	s.importMappings[ledger] = next
	if err := s.saveMeta(); err != nil {
		s.importMappings[ledger] = old
		return 0, "账单已保存，但分类映射未记住，请稍后重试"
	}
	return n, ""
}

func (s *Store) ImportFormat(ledger, key string) map[string]int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := map[string]int{}
	for f, i := range s.importFormats[ledger+":"+key] {
		out[f] = i
	}
	return out
}
func (s *Store) RememberImportFormat(ledger, key string, mapping map[string]int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(key) != 64 || len(mapping) == 0 {
		return nil
	}
	clean := map[string]int{}
	for f, i := range mapping {
		if f != "NATURE" && f != "TIME_EVIDENCE" && i >= 0 && i < 256 {
			clean[f] = i
		}
	}
	if _, ok := clean["TIME"]; !ok {
		return nil
	}
	next := map[string]map[string]int{}
	for k, v := range s.importFormats {
		next[k] = v
	}
	k := ledger + ":" + key
	next[k] = clean
	keys := []string{}
	for old := range next {
		if old != k {
			keys = append(keys, old)
		}
	}
	sort.Strings(keys)
	for _, old := range keys {
		if len(next) <= 128 {
			break
		}
		delete(next, old)
	}
	previous := s.importFormats
	s.importFormats = next
	if err := s.saveMeta(); err != nil {
		s.importFormats = previous
		return err
	}
	return nil
}
