package store

import (
	"path/filepath"
	"testing"
)

func TestKVAndPersistence(t *testing.T) {
	path := filepath.Join(t.TempDir(), "desktop.db")

	db, err := OpenAt(path)
	if err != nil {
		t.Fatalf("OpenAt: %v", err)
	}

	if _, found, err := db.Get("missing"); err != nil || found {
		t.Fatalf("missing key: found=%v err=%v", found, err)
	}

	if err := db.Set("settings.theme", "dark"); err != nil {
		t.Fatalf("Set: %v", err)
	}
	if err := db.Set("settings.theme", "light"); err != nil {
		t.Fatalf("Set update: %v", err)
	}
	if value, found, err := db.Get("settings.theme"); err != nil || !found || value != "light" {
		t.Fatalf("Get: value=%q found=%v err=%v", value, found, err)
	}

	if err := db.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	reopened, err := OpenAt(path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer reopened.Close()

	if value, found, err := reopened.Get("settings.theme"); err != nil || !found || value != "light" {
		t.Fatalf("reopened Get: value=%q found=%v err=%v", value, found, err)
	}
	if err := reopened.Delete("settings.theme"); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, found, err := reopened.Get("settings.theme"); err != nil || found {
		t.Fatalf("after Delete: found=%v err=%v", found, err)
	}
}

func TestSessionToken(t *testing.T) {
	db, err := OpenAt(filepath.Join(t.TempDir(), "desktop.db"))
	if err != nil {
		t.Fatalf("OpenAt: %v", err)
	}
	defer db.Close()

	if _, found, err := db.Token(); err != nil || found {
		t.Fatalf("empty token: found=%v err=%v", found, err)
	}

	if err := db.SetToken("t-1"); err != nil {
		t.Fatalf("SetToken: %v", err)
	}
	if err := db.SetToken("t-2"); err != nil {
		t.Fatalf("SetToken update: %v", err)
	}
	if token, found, err := db.Token(); err != nil || !found || token != "t-2" {
		t.Fatalf("Token: value=%q found=%v err=%v", token, found, err)
	}

	if err := db.ClearToken(); err != nil {
		t.Fatalf("ClearToken: %v", err)
	}
	if _, found, err := db.Token(); err != nil || found {
		t.Fatalf("after ClearToken: found=%v err=%v", found, err)
	}
}
