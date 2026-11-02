package main

import (
	"strings"
	"testing"
)

func TestNewPricesCannotBeEnabledBeforeTermAcceptance(t *testing.T) {
	t.Setenv("COMMERCE_PAYMENTS_ENABLED", "true")
	_, err := config()
	if err == nil || !strings.Contains(err.Error(), "closed preview") {
		t.Fatal("unaccepted pricing can collect money", err)
	}
}
