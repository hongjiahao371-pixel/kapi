package store

import (
 "encoding/json"
 "testing"
 "kapi/sync/internal/merge"
)

func TestBusinessTimeEvidenceSurvivesOtherFieldEditAndRestart(t *testing.T) {
 s,dir:=newTestStore(t)
 evidence:=`{"selectedAt":1791243886000,"source":"PAYMENT","precision":"SECOND","paidAt":1791243886000,"createdAt":1791151855000,"capturedAt":1791620000000,"malformed":false}`
 c:=txn("phone","time-proof",10,2500)
 c.Data["time_evidence_json"]=merge.V(evidence)
 c.FV["time_evidence_json"]=merge.FVEntry{L:10,D:"phone"}
 if _,err:=s.Push("dev_a",[]merge.Change{c});err!=nil {t.Fatal(err)}
 edit:=txn("ios","time-proof",20,2500)
 if _,err:=s.Push("dev_b",[]merge.Change{edit});err!=nil {t.Fatal(err)}
 if err:=s.Close();err!=nil {t.Fatal(err)}
 restored,err:=Open(dir);if err!=nil {t.Fatal(err)};defer restored.Close()
 row,ok:=restored.Row("txn","time-proof");if !ok {t.Fatal("missing transaction")}
 var got string
 if err=json.Unmarshal(row.Values["time_evidence_json"],&got);err!=nil {t.Fatal(err)}
 if got!=evidence {t.Fatal("business time evidence was changed or lost")}
 if string(row.Values["amount_minor"])!="2500" {t.Fatal("money bytes changed")}
}
