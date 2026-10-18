package store

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type BackupInfo struct {
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	CreatedAt int64  `json:"created_at"`
}
type backupManifest struct {
	Format  string            `json:"format"`
	Version int               `json:"version"`
	Seq     int64             `json:"seq"`
	SHA256  map[string]string `json:"sha256"`
}

func digest(b []byte) string { sum := sha256.Sum256(b); return hex.EncodeToString(sum[:]) }

// CreateBackup holds the commit lock while reading log and metadata: the archive is one consistent point in time.
func (s *Store) CreateBackup(force bool) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	dir := filepath.Join(s.dir, "backups")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	files, _ := filepath.Glob(filepath.Join(dir, "kapi-*.zip"))
	sort.Strings(files)
	if !force && len(files) > 0 {
		info, err := os.Stat(files[len(files)-1])
		if err == nil && time.Since(info.ModTime()) < 24*time.Hour {
			return files[len(files)-1], nil
		}
	}
	if err := s.logFile.Sync(); err != nil {
		return "", err
	}
	if err := s.saveMeta(); err != nil {
		return "", err
	}
	content := map[string][]byte{}
	for _, name := range []string{"log.jsonl", "meta.json"} {
		b, err := os.ReadFile(filepath.Join(s.dir, name))
		if err != nil {
			return "", err
		}
		content[name] = b
	}
	manifest := backupManifest{Format: "kapi-nas-backup", Version: 1, Seq: s.seq, SHA256: map[string]string{}}
	for name, b := range content {
		manifest.SHA256[name] = digest(b)
	}
	content["manifest.json"], _ = json.Marshal(manifest)
	filename := fmt.Sprintf("kapi-%s-%09d.zip", time.Now().UTC().Format("20060102T150405.000000000"), s.seq)
	target := filepath.Join(dir, filename)
	file, err := os.OpenFile(target+".tmp", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	zw := zip.NewWriter(file)
	for _, name := range []string{"manifest.json", "log.jsonl", "meta.json"} {
		w, e := zw.Create(name)
		if e != nil {
			err = e
			break
		}
		_, err = w.Write(content[name])
		if err != nil {
			break
		}
	}
	if e := zw.Close(); err == nil {
		err = e
	}
	if e := file.Sync(); err == nil {
		err = e
	}
	if e := file.Close(); err == nil {
		err = e
	}
	if err != nil {
		os.Remove(target + ".tmp")
		return "", err
	}
	if err = os.Rename(target+".tmp", target); err != nil {
		return "", err
	}
	files = append(files, target)
	sort.Strings(files)
	for len(files) > 14 {
		os.Remove(files[0])
		files = files[1:]
	}
	return target, nil
}
func (s *Store) Backups() ([]BackupInfo, error) {
	files, err := filepath.Glob(filepath.Join(s.dir, "backups", "kapi-*.zip"))
	if err != nil {
		return nil, err
	}
	result := make([]BackupInfo, 0, len(files))
	for _, path := range files {
		info, e := os.Stat(path)
		if e != nil {
			return nil, e
		}
		result = append(result, BackupInfo{filepath.Base(path), info.Size(), info.ModTime().UnixMilli()})
	}
	sort.Slice(result, func(i, j int) bool { return result[i].Name > result[j].Name })
	return result, nil
}

// RestoreBackup only accepts an empty destination. Validate checksums and replay in a staging directory before the atomic rename.
func RestoreBackup(archivePath, dataDir string) error {
	if list, err := os.ReadDir(dataDir); err == nil && len(list) > 0 {
		return fmt.Errorf("恢复目标不是空目录，拒绝覆盖已有账本")
	} else if err != nil && !os.IsNotExist(err) {
		return err
	}
	zr, err := zip.OpenReader(archivePath)
	if err != nil {
		return err
	}
	defer zr.Close()
	content := map[string][]byte{}
	for _, file := range zr.File {
		if file.Name != "log.jsonl" && file.Name != "meta.json" && file.Name != "manifest.json" {
			return fmt.Errorf("备份包含不允许的路径")
		}
		if _, ok := content[file.Name]; ok {
			return fmt.Errorf("备份包含重复文件")
		}
		if file.UncompressedSize64 > 128<<20 {
			return fmt.Errorf("备份文件过大")
		}
		in, e := file.Open()
		if e != nil {
			return e
		}
		b, e := io.ReadAll(io.LimitReader(in, (128<<20)+1))
		in.Close()
		if e != nil {
			return e
		}
		if len(b) > 128<<20 {
			return fmt.Errorf("备份文件过大")
		}
		content[file.Name] = b
	}
	if len(content) != 3 {
		return fmt.Errorf("备份文件不完整")
	}
	var manifest backupManifest
	if err = json.Unmarshal(content["manifest.json"], &manifest); err != nil {
		return err
	}
	if manifest.Format != "kapi-nas-backup" || manifest.Version != 1 {
		return fmt.Errorf("不支持的备份格式")
	}
	for _, name := range []string{"log.jsonl", "meta.json"} {
		if !strings.EqualFold(manifest.SHA256[name], digest(content[name])) {
			return fmt.Errorf("备份校验失败: %s", name)
		}
	}
	parent := filepath.Dir(dataDir)
	if err = os.MkdirAll(parent, 0700); err != nil {
		return err
	}
	temp, err := os.MkdirTemp(parent, ".kapi-restore-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(temp)
	for _, name := range []string{"log.jsonl", "meta.json"} {
		if err = os.WriteFile(filepath.Join(temp, name), content[name], 0600); err != nil {
			return err
		}
	}
	st, err := Open(temp)
	if err != nil {
		return err
	}
	h := st.HealthInfo()
	err = st.Close()
	if err != nil {
		return err
	}
	if h.DBSeq != manifest.Seq {
		return fmt.Errorf("备份游标不一致")
	}
	// Refuse trailing corrupt/truncated log recovery as well: restoring must reproduce every byte and sequence.
	replayed, err := os.ReadFile(filepath.Join(temp, "log.jsonl"))
	if err != nil {
		return err
	}
	if !bytes.Equal(replayed, content["log.jsonl"]) {
		return fmt.Errorf("备份日志未完整重放")
	}
	if err = os.Rename(temp, dataDir); err != nil {
		return err
	}
	return nil
}
