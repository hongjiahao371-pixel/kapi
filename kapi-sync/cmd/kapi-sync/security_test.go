package main

import (
	"bytes"
	"kapi/sync/internal/store"
	"log"
	"strings"
	"testing"
)

func TestStartupDoesNotLogPairingOrPanelSecrets(t *testing.T) {
	var b bytes.Buffer
	old := log.Writer()
	log.SetOutput(&b)
	defer log.SetOutput(old)
	printBanner("0.0.0.0:8787", "/data", "unique-test-pair-secret", "unique-test-panel-secret", store.Health{})
	if strings.Contains(b.String(), "unique-test-") {
		t.Fatal("startup leaked credential")
	}
}
