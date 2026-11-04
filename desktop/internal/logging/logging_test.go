package logging

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWriteRedactsSensitiveFields(t *testing.T) {
	service, err := NewAt(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("NewAt: %v", err)
	}
	defer service.Close()

	if err := service.Write("error", "api.request", "请求失败", map[string]string{
		"path":     "/api/desktop/notes",
		"token":    "secret-token",
		"content":  "笔记正文",
		"password": "p@ss",
		"err":      "connection refused",
	}); err != nil {
		t.Fatalf("Write: %v", err)
	}

	data, err := os.ReadFile(service.LogPath())
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	text := string(data)
	for _, secret := range []string{"secret-token", "笔记正文", "p@ss"} {
		if strings.Contains(text, secret) {
			t.Fatalf("日志泄露敏感内容 %q: %s", secret, text)
		}
	}
	for _, want := range []string{"/api/desktop/notes", "connection refused", `"token":"***"`} {
		if !strings.Contains(text, want) {
			t.Fatalf("日志缺少 %q: %s", want, text)
		}
	}
}

func TestRecentKeepsNewest(t *testing.T) {
	service, err := NewAt(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("NewAt: %v", err)
	}
	defer service.Close()

	for _, event := range []string{"a", "b", "c"} {
		service.log(LevelInfo, event, "", nil)
	}
	recent := service.Recent(2)
	if len(recent) != 2 || recent[0].Event != "b" || recent[1].Event != "c" {
		t.Fatalf("Recent = %+v", recent)
	}
	if all := service.Recent(0); len(all) != 3 {
		t.Fatalf("Recent(0) = %d", len(all))
	}
}

func TestLevelFilter(t *testing.T) {
	service, err := NewAt(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("NewAt: %v", err)
	}
	defer service.Close()
	service.SetMinLevel(LevelWarn)

	service.log(LevelInfo, "ignored", "", nil)
	service.log(LevelError, "kept", "", nil)

	recent := service.Recent(0)
	if len(recent) != 1 || recent[0].Event != "kept" {
		t.Fatalf("Recent = %+v", recent)
	}
}

func TestRotation(t *testing.T) {
	dir := t.TempDir()
	service, err := NewAt(dir, "test")
	if err != nil {
		t.Fatalf("NewAt: %v", err)
	}
	service.maxSize = 200
	service.maxBackups = 2

	for i := 0; i < 40; i++ {
		service.log(LevelInfo, "rotate", strings.Repeat("x", 60), nil)
	}
	service.Close()

	for _, name := range []string{"desktop.log", "desktop.log.1", "desktop.log.2"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			t.Fatalf("缺少轮转文件 %s: %v", name, err)
		}
	}
	if _, err := os.Stat(filepath.Join(dir, "desktop.log.3")); !os.IsNotExist(err) {
		t.Fatalf("保留的备份超过上限: %v", err)
	}
}

func TestDiagnosticsIncludesEnvironmentButNoSecrets(t *testing.T) {
	service, err := NewAt(t.TempDir(), "9.9.9")
	if err != nil {
		t.Fatalf("NewAt: %v", err)
	}
	defer service.Close()
	service.log(LevelError, "db.migrate", "失败", map[string]string{"token": "abc"})

	text := service.Diagnostics()
	for _, want := range []string{"Simple Notes 9.9.9", "Log:", "Recent entries: 1", `"token":"***"`} {
		if !strings.Contains(text, want) {
			t.Fatalf("Diagnostics 缺少 %q:\n%s", want, text)
		}
	}
	if strings.Contains(text, "abc") {
		t.Fatalf("Diagnostics 泄露令牌:\n%s", text)
	}
}
