package store_test

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"io"
	"kapi/sync/internal/merge"
	"kapi/sync/internal/store"
	"os"
	"path/filepath"
	"testing"
)

func TestBackupRestoresLogDevicesAndDeletedRows(t *testing.T) {
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	code, _ := st.PairCode()
	paired, err := st.Pair(code, "backup-phone")
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Push(paired.DeviceID, []merge.Change{{ChangeID: "b1", Entity: "txn", EntityID: "one", Op: "upsert", Data: map[string]json.RawMessage{"amount_minor": merge.V(int64(9007199254740993))}, Lamport: 1, Origin: paired.DeviceID}, {ChangeID: "b2", Entity: "budget", EntityID: "budget1", Op: "upsert", Data: map[string]json.RawMessage{"payload": merge.V("{\"amountMinor\":2000}")}, Lamport: 2, Origin: paired.DeviceID}, {ChangeID: "b3", Entity: "txn", EntityID: "one", Op: "delete", Lamport: 3, Origin: paired.DeviceID}})
	if err != nil {
		t.Fatal(err)
	}
	path, err := st.CreateBackup(true)
	if err != nil {
		t.Fatal(err)
	}
	dest := filepath.Join(t.TempDir(), "restored")
	if err = store.RestoreBackup(path, dest); err != nil {
		t.Fatal(err)
	}
	restored, err := store.Open(dest)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	if restored.HealthInfo().DBSeq != st.HealthInfo().DBSeq {
		t.Fatal("cursor differs")
	}
	if _, err = restored.Authenticate(paired.Token); err != nil {
		t.Fatal("paired token was not restored")
	}
	row, ok := restored.Row("txn", "one")
	if !ok || !row.Deleted {
		t.Fatal("tombstone lost")
	}
	if string(row.Values["amount_minor"]) != "9007199254740993" {
		t.Fatal("integer precision lost")
	}
	if _, ok = restored.Row("budget", "budget1"); !ok {
		t.Fatal("configuration lost")
	}
	if err = store.RestoreBackup(path, dest); err == nil {
		t.Fatal("nonempty destination overwritten")
	}
}
func TestBackupRetentionAndReuse(t *testing.T) {
	st, _ := store.Open(t.TempDir())
	defer st.Close()
	first, err := st.CreateBackup(false)
	if err != nil {
		t.Fatal(err)
	}
	second, err := st.CreateBackup(false)
	if err != nil || first != second {
		t.Fatal("automatic backup duplicated within a day")
	}
	for i := 0; i < 17; i++ {
		if _, err = st.CreateBackup(true); err != nil {
			t.Fatal(err)
		}
	}
	list, err := st.Backups()
	if err != nil || len(list) != 14 {
		t.Fatalf("retention: %d %v", len(list), err)
	}
}
func TestRestoreRejectsBadChecksumAndZipPathsBeforeWriting(t *testing.T) {
	st, _ := store.Open(t.TempDir())
	defer st.Close()
	path, _ := st.CreateBackup(true)
	original, _ := zip.OpenReader(path)
	defer original.Close()
	for _, badPath := range []bool{false, true} {
		var out bytes.Buffer
		zw := zip.NewWriter(&out)
		for _, f := range original.File {
			r, _ := f.Open()
			b, _ := io.ReadAll(r)
			r.Close()
			name := f.Name
			if name == "log.jsonl" {
				if badPath {
					name = "../log.jsonl"
				} else {
					b = append(b, 'x')
				}
			}
			w, _ := zw.Create(name)
			w.Write(b)
		}
		zw.Close()
		dir := t.TempDir()
		bad := filepath.Join(dir, "bad.zip")
		os.WriteFile(bad, out.Bytes(), 0600)
		dest := filepath.Join(dir, "destination")
		if err := store.RestoreBackup(bad, dest); err == nil {
			t.Fatal("corrupt archive accepted")
		}
		if _, err := os.Stat(dest); !os.IsNotExist(err) {
			t.Fatal("destination changed before validation")
		}
	}
}
