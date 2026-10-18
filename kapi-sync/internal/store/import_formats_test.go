package store

import (
	"strings"
	"testing"
)

func TestImportFormatMappingsAreScopedBoundedAndSurviveRestart(t *testing.T) {
	dir := t.TempDir()
	s, e := Open(dir)
	if e != nil {
		t.Fatal(e)
	}
	key := strings.Repeat("a", 64)
	mapping := map[string]int{"TIME": 0, "AMOUNT": 2, "NATURE": 3, "TIME_EVIDENCE": 4}
	if e = s.RememberImportFormat("L1", key, mapping); e != nil {
		t.Fatal(e)
	}
	if len(s.ImportFormat("other", key)) != 0 || len(s.ImportFormat("L1", key)) != 2 {
		t.Fatal("scope or review values persisted")
	}
	s.Close()
	s, e = Open(dir)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if s.ImportFormat("L1", key)["AMOUNT"] != 2 {
		t.Fatal("mapping lost on restart")
	}
	m := s.ImportFormat("L1", key)
	m["AMOUNT"] = 99
	if s.ImportFormat("L1", key)["AMOUNT"] != 2 {
		t.Fatal("map alias mutated store")
	}
}
