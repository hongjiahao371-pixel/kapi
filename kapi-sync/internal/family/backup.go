package family

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// CreateBackups retains each member's snapshots inside that member's private
// directory. A root phone's backup remains private to the owner; it never gains
// access to everyone's records through a legacy /v1/backup token.
func (h *Hub) CreateBackups() error {
	h.mu.RLock()
	defer h.mu.RUnlock()
	if !h.state.Enabled {
		return nil
	}
	if _, err := h.shared.st.CreateBackup(false); err != nil {
		return fmt.Errorf("family shared backup: %w", err)
	}
	for id, space := range h.private {
		if _, err := space.st.CreateBackup(false); err != nil {
			return fmt.Errorf("family member %s backup: %w", id, err)
		}
	}
	dir := filepath.Join(h.dir, "backups")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(h.state, "", "  ")
	if err != nil {
		return err
	}
	name := "family-" + h.now().Format("2006-01-02") + ".json"
	temp := filepath.Join(dir, name+".tmp")
	file, err := os.OpenFile(temp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	if _, err = file.Write(b); err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = os.Rename(temp, filepath.Join(dir, name)); err != nil {
		return err
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	names := []string{}
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasPrefix(entry.Name(), "family-") && strings.HasSuffix(entry.Name(), ".json") {
			names = append(names, entry.Name())
		}
	}
	sort.Strings(names)
	for len(names) > 14 {
		if err = os.Remove(filepath.Join(dir, names[0])); err != nil {
			return err
		}
		names = names[1:]
	}
	return nil
}
