// Package billimport runs the exact Android core reader/matcher locally, on demand.
package billimport

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"time"
)

type Row struct {
	ExcludeFromBudget bool            `json:"excludeFromBudget"`
	OccurredAt        int64           `json:"occurredAt"`
	Direction         string          `json:"direction"`
	AmountMinor       int64           `json:"amountMinor"`
	Merchant          *string         `json:"merchant"`
	Note              *string         `json:"note"`
	Category          *string         `json:"category"`
	Account           *string         `json:"account"`
	ToAccount         *string         `json:"toAccount"`
	Source            string          `json:"source"`
	ExternalKey       *string         `json:"externalKey"`
	TimeEvidence      json.RawMessage `json:"timeEvidence"`
}
type Existing struct {
	ID       string `json:"id"`
	LedgerID string `json:"ledgerId"`
	Deleted  bool   `json:"deleted"`
	Row      Row    `json:"row"`
}
type Edit struct {
	Row     int               `json:"row"`
	Changes map[string]string `json:"changes"`
}
type Candidate struct {
	AccountHints    []string `json:"accountHints,omitempty"`
	Row             Row      `json:"row"`
	Match           string   `json:"match"`
	Reason          string   `json:"reason"`
	ExistingID      string   `json:"existingId"`
	ExistingDeleted bool     `json:"existingDeleted"`
	ImportID        string   `json:"importId"`
	AccountID       string   `json:"accountId"`
	ToAccountID     string   `json:"toAccountId"`
	CategoryID      string   `json:"categoryId"`
	ExistingRow     *Row     `json:"existingRow,omitempty"`
	ExistingCount   int      `json:"existingCount"`
}
type Issue struct {
	Row    int    `json:"row"`
	Reason string `json:"reason"`
}
type Sheet struct {
	Name    string `json:"name"`
	Records int    `json:"records"`
	Status  string `json:"status"`
}
type Result struct {
	FormatKey     string            `json:"formatKey"`
	Checks        []string          `json:"checks"`
	Rows          []Row             `json:"rows"`
	Candidates    []Candidate       `json:"candidates"`
	Source        string            `json:"source"`
	SourceName    string            `json:"sourceName"`
	IncomeMinor   int64             `json:"incomeMinor"`
	ExpenseMinor  int64             `json:"expenseMinor"`
	IssueCount    int               `json:"issueCount"`
	Issues        []Issue           `json:"issues"`
	Neutral       int               `json:"neutral"`
	Unpaid        int               `json:"unpaid"`
	Duplicates    int               `json:"duplicates"`
	Sheets        []Sheet           `json:"sheets"`
	SourceRecords int               `json:"sourceRecords"`
	CorrectedRows int               `json:"correctedRows"`
	RepairValues  map[string]string `json:"repairValues,omitempty"`
	FieldPrompt   string            `json:"fieldPrompt"`
	Mapping       map[string]int    `json:"mapping"`
	Profile       string            `json:"profile"`
	Error         string            `json:"error"`
}
type Request struct {
	Mode      string         `json:"mode"`
	File      string         `json:"file"`
	LedgerID  string         `json:"ledgerId"`
	Rows      []Row          `json:"rows,omitempty"`
	Existing  []Existing     `json:"existing,omitempty"`
	Edits     []Edit         `json:"edits,omitempty"`
	RepairRow int            `json:"repairRow,omitempty"`
	Mapping   map[string]int `json:"mapping,omitempty"`
}
type Worker struct {
	Jar, Java string
	slot      chan struct{}
}

func NewWorker() *Worker {
	jar := os.Getenv("KAPI_IMPORT_JAR")
	if jar == "" {
		jar = "/opt/guizhang/guizhang-import.jar"
	}
	java := os.Getenv("KAPI_IMPORT_JAVA")
	if java == "" {
		java = "java"
	}
	return &Worker{Jar: jar, Java: java, slot: make(chan struct{}, 1)}
}
func (w *Worker) Available() bool { _, err := os.Stat(w.Jar); return err == nil }

type boundedBuffer struct{ bytes.Buffer }

func (b *boundedBuffer) Write(p []byte) (int, error) {
	if b.Len()+len(p) > 16*1024*1024 {
		return 0, errors.New("读取结果过大，请分批导入")
	}
	return b.Buffer.Write(p)
}
func (w *Worker) Run(ctx context.Context, r Request) (*Result, error) {
	if !w.Available() {
		return nil, errors.New("NAS 尚未安装账单读取组件，请更新完整 Docker 镜像")
	}
	select {
	case w.slot <- struct{}{}:
		defer func() { <-w.slot }()
	default:
		return nil, errors.New("正在核对另一份账单，请稍后重试")
	}
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	data, err := json.Marshal(r)
	if err != nil {
		return nil, err
	}
	if len(data) > 64*1024*1024 {
		return nil, errors.New("账本过大，请分批导入")
	}
	cmd := exec.CommandContext(ctx, w.Java, "-Xmx256m", "-Duser.timezone=Asia/Shanghai", "-jar", w.Jar)
	cmd.Stdin = bytes.NewReader(data)
	var out boundedBuffer
	cmd.Stdout = &out
	if err = cmd.Run(); err != nil {
		return nil, errors.New("账单读取失败或超时，请检查文件并按月份分批导入")
	}
	var result Result
	if err = json.Unmarshal(out.Bytes(), &result); err != nil {
		return nil, errors.New("读取结果无效，请更新账单读取组件")
	}
	if result.Error != "" {
		return nil, errors.New(result.Error)
	}
	return &result, nil
}
