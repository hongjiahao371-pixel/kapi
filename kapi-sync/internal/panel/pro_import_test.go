package panel

import (
	"encoding/json"
	"os"
	"testing"
)

func TestFreeFormatRepairImportsAndRepeatedRemappingDoesNotDuplicate(t *testing.T) {
	if os.Getenv("KAPI_IMPORT_JAR") == "" {
		t.Skip("real import core required")
	}
	st, _, mux := importFixture(t)
	reviewSeed(t, st)
	if st.Pro.Enabled() {
		t.Fatal("requires fresh free fixture")
	}
	data := []byte("发生日,流向,发生额,附言\n2026-10-10,支出,12.35,测试商户")
	var originalFormat string
	for iteration := 0; iteration < 2; iteration++ {
		p := importUpload(t, mux, data)
		r := reviewPost(mux, "/v1/panel/import/format-apply", map[string]any{"ticket": p.Ticket, "revision": p.Revision, "mapping": map[string]int{"TIME": 0, "DIRECTION": 1, "AMOUNT": 2, "NOTE": 3}})
		if r.Code != 200 {
			t.Fatal(r.Body.String())
		}
		var reviewed importPreview
		if e := json.Unmarshal(r.Body.Bytes(), &reviewed); e != nil {
			t.Fatal(e)
		}
		if len(reviewed.Candidates) != 1 {
			t.Fatal("corrected row missing")
		}
		originalFormat = reviewed.Parsed.FormatKey
		if iteration == 1 {
			if reviewed.Candidates[0].Match == "NEW" {
				t.Fatal("repeat became new")
			}
			break
		}
		saved := reviewPost(mux, "/v1/panel/import/commit", map[string]any{"ticket": reviewed.Ticket, "revision": reviewed.Revision, "selected": []int{0}, "fallbackAccountId": "A1", "allowUnclassified": true, "acknowledgeIssues": true, "rememberMappings": true})
		if saved.Code != 200 {
			t.Fatal("free corrected import denied", saved.Body.String())
		}
	}
	if len(st.ImportFormat("L1", originalFormat)) != 0 {
		t.Fatal("free install saved a Pro template")
	}
	if st.Pro.Enabled() {
		t.Fatal("import escalated entitlement")
	}
}
