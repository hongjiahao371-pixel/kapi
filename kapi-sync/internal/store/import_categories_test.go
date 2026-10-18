package store

import (
	"encoding/json"
	"kapi/sync/internal/merge"
	"os"
	"path/filepath"
	"testing"
)

func TestImportCategoryPathsAcceptLegacyQualifiedNames(t *testing.T) {
	s, _ := newTestStore(t)
	_, err := s.Push("seed", []merge.Change{
		{ChangeID: "p", Entity: "category", EntityID: "P", Op: "upsert", Lamport: 1, Origin: "seed", Data: map[string]json.RawMessage{"name": merge.V("餐饮"), "kind": merge.V("EXPENSE"), "ledger_id": merge.V("L1")}},
		{ChangeID: "c", Entity: "category", EntityID: "C", Op: "upsert", Lamport: 2, Origin: "seed", Data: map[string]json.RawMessage{"name": merge.V("餐饮/正餐"), "kind": merge.V("EXPENSE"), "ledger_id": merge.V("L1"), "parent_id": merge.V("P")}},
		{ChangeID: "n", Entity: "category", EntityID: "N", Op: "upsert", Lamport: 3, Origin: "seed", Data: map[string]json.RawMessage{"name": merge.V("早餐"), "kind": merge.V("EXPENSE"), "ledger_id": merge.V("L1"), "parent_id": merge.V("P")}},
	})
	if err != nil {
		t.Fatal(err)
	}
	_, _, catalog := s.BillImportSnapshot("L1")
	paths := map[string]string{}
	for _, c := range catalog.Categories {
		paths[c.ID] = c.Path
	}
	if paths["C"] != "餐饮/正餐" || paths["N"] != "餐饮/早餐" {
		t.Fatal(paths)
	}
}

func TestImportCategoryMappingsPersistAndStayScoped(t *testing.T) {
	s, dir := newTestStore(t)
	s.mu.Lock()
	n, warning := s.rememberImportMappingsLocked("L1", map[string]map[string]bool{"source\x1fEXPENSE\x1f餐饮": {"MEAL": true}})
	s.mu.Unlock()
	if n != 1 || warning != "" {
		t.Fatal(n, warning)
	}
	copy := s.ImportCategoryMappings("L1")
	copy["source\x1fEXPENSE\x1f餐饮"] = "OTHER"
	if len(s.ImportCategoryMappings("L2")) != 0 || s.ImportCategoryMappings("L1")["source\x1fEXPENSE\x1f餐饮"] != "MEAL" {
		t.Fatal("scope or copy mutated")
	}
	s.Close()
	reopened, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if reopened.ImportCategoryMappings("L1")["source\x1fEXPENSE\x1f餐饮"] != "MEAL" {
		t.Fatal("restart lost mapping")
	}
	reopened.mu.Lock()
	_, warning = reopened.rememberImportMappingsLocked("L1", map[string]map[string]bool{"source\x1fEXPENSE\x1f餐饮": {"MEAL": true, "OTHER": true}})
	reopened.mu.Unlock()
	if warning != "" || len(reopened.ImportCategoryMappings("L1")) != 0 {
		t.Fatal("conflicting choices were remembered")
	}
}

func TestFailedMappingPersistenceRestoresPreviousMapping(t *testing.T) {
	s, dir := newTestStore(t)
	// A directory at the atomic temporary-file path forces a real disk-write failure.
	if err := os.Mkdir(filepath.Join(dir, "meta.json.tmp"), 0700); err != nil {
		t.Fatal(err)
	}
	s.mu.Lock()
	n, warning := s.rememberImportMappingsLocked("L1", map[string]map[string]bool{"source\x1fEXPENSE\x1f餐饮": {"MEAL": true}})
	s.mu.Unlock()
	if n != 0 || warning == "" || len(s.ImportCategoryMappings("L1")) != 0 {
		t.Fatal("failed mapping persisted in memory")
	}
}
