package localfile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"cc.naihao/simplenotes/desktop/internal/store"
)

func openTestService(t *testing.T) *Service {
	t.Helper()
	db, err := store.OpenAt(filepath.Join(t.TempDir(), "desktop.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return New(db)
}

func writeTempFile(t *testing.T, name string, content []byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, content, 0o644); err != nil {
		t.Fatalf("write temp file: %v", err)
	}
	return path
}

func TestReadFileDataStripsBOMAndDetectsCRLF(t *testing.T) {
	path := writeTempFile(t, "note.md", append([]byte{0xEF, 0xBB, 0xBF}, []byte("第一行\r\n第二行\r\n")...))
	data, err := readFileData(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if data.Content != "第一行\r\n第二行\r\n" {
		t.Fatalf("content = %q", data.Content)
	}
	if data.LineEnding != "crlf" {
		t.Fatalf("line ending = %q", data.LineEnding)
	}
	if !data.BOM {
		t.Fatal("BOM 标记丢失")
	}
}

func TestReadFileDataRejectsInvalidTypeAndEncoding(t *testing.T) {
	if _, err := readFileData(writeTempFile(t, "note.txt", []byte("x"))); err == nil {
		t.Fatal("非 Markdown 应被拒绝")
	}
	if _, err := readFileData(writeTempFile(t, "note", []byte("x"))); err == nil {
		t.Fatal("无扩展名应被拒绝")
	}
	if _, err := readFileData(writeTempFile(t, "bad.md", []byte{0xff, 0xfe, 0xfd})); err == nil {
		t.Fatal("非 UTF-8 应被拒绝")
	}
}

func TestSaveDetectsExternalModificationAndPreservesLineEnding(t *testing.T) {
	path := writeTempFile(t, "note.md", []byte("old\r\nline\r\n"))
	data, err := readFileData(path)
	if err != nil {
		t.Fatal(err)
	}

	svc := openTestService(t)
	modified, err := svc.Save(SaveFileInput{
		Path:         path,
		Content:      "new\nline\n",
		ExpectedHash: "stale-hash",
	})
	if err != nil {
		t.Fatal(err)
	}
	if modified.State != "modified" {
		t.Fatalf("state = %q, want modified", modified.State)
	}

	saved, err := svc.Save(SaveFileInput{
		Path:         path,
		Content:      "new\nline\n",
		ExpectedHash: data.Hash,
		Force:        true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if saved.State != "saved" {
		t.Fatalf("state = %q, want saved", saved.State)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) != "new\r\nline\r\n" {
		t.Fatalf("换行未保留: %q", string(raw))
	}
	if saved.Hash == data.Hash {
		t.Fatal("保存后 hash 应变化")
	}
}

func TestSaveMissingFile(t *testing.T) {
	svc := openTestService(t)
	path := filepath.Join(t.TempDir(), "missing.md")
	result, err := svc.Save(SaveFileInput{Path: path, Content: "x", ExpectedHash: "h"})
	if err != nil {
		t.Fatal(err)
	}
	if result == nil || result.State != "missing" {
		t.Fatalf("result = %+v", result)
	}
}

func TestSavePreservesBOM(t *testing.T) {
	path := writeTempFile(t, "bom.md", append([]byte{0xEF, 0xBB, 0xBF}, []byte("old\n")...))
	data, err := readFileData(path)
	if err != nil {
		t.Fatal(err)
	}
	svc := openTestService(t)
	result, err := svc.Save(SaveFileInput{
		Path:         path,
		Content:      "new\n",
		ExpectedHash: data.Hash,
	})
	if err != nil || result == nil || result.State != "saved" {
		t.Fatalf("save = %+v, err=%v", result, err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(raw) < 3 || raw[0] != 0xEF || raw[1] != 0xBB || raw[2] != 0xBF {
		t.Fatalf("BOM 未保留: %x", raw)
	}
}

func TestDefaultMarkdownName(t *testing.T) {
	cases := map[string]string{
		"a/b:c*?":       "a-b-c--.md",
		"  ..  ":        "未命名.md",
		"标题":            "标题.md",
		"already.md":    "already.md",
		"note.markdown": "note.markdown",
	}
	for input, want := range cases {
		if got := defaultMarkdownName(input); got != want {
			t.Fatalf("defaultMarkdownName(%q) = %q, want %q", input, got, want)
		}
	}
	if len([]rune(defaultMarkdownName(strings.Repeat("长", 200)))) > 103 {
		t.Fatal("默认文件名未限制长度")
	}
}
