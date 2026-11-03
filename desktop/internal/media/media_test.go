package media

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/store"
)

var testPNG = []byte{
	0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
	0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
	0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
	0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
	0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
	0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
	0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
	0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
	0x42, 0x60, 0x82,
}

func newTestService(t *testing.T) (*Service, *store.Store) {
	t.Helper()
	db, err := store.OpenAt(filepath.Join(t.TempDir(), "desktop.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return New(db, nil), db
}

func TestDetectImage(t *testing.T) {
	for name, header := range map[string][]byte{
		"png":  testPNG,
		"jpeg": {0xFF, 0xD8, 0xFF, 0xE0},
		"gif":  []byte("GIF89a"),
		"webp": append([]byte("RIFF\x00\x00\x00\x00WEBP"), 0),
		"avif": append([]byte("\x00\x00\x00\x18ftypavif"), 0),
	} {
		if _, err := detectImage(header); err != nil {
			t.Fatalf("%s should be accepted: %v", name, err)
		}
	}
	if _, err := detectImage([]byte("<svg xmlns=\"http://www.w3.org/2000/svg\">")); err == nil {
		t.Fatal("svg must be rejected")
	}
}

func TestHandleImportLocal(t *testing.T) {
	service, _ := newTestService(t)
	req := httptest.NewRequest(http.MethodPost, "/import?target=local&name=shot.png", bytes.NewReader(testPNG))
	req.Header.Set("Content-Type", "image/png")
	recorder := httptest.NewRecorder()
	service.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s", recorder.Code, recorder.Body.String())
	}
	var result ImportResult
	if err := json.Unmarshal(recorder.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(result.Ref, LocalRefPrefix) {
		t.Fatalf("ref = %q", result.Ref)
	}
	serve := httptest.NewRequest(http.MethodGet, strings.TrimPrefix(result.URL, "/__media"), nil)
	serveRecorder := httptest.NewRecorder()
	service.ServeHTTP(serveRecorder, serve)
	if serveRecorder.Code != http.StatusOK {
		t.Fatalf("serve status = %d", serveRecorder.Code)
	}
}

func TestHandleImportFile(t *testing.T) {
	service, _ := newTestService(t)
	dir := t.TempDir()
	mdPath := filepath.Join(dir, "note.md")
	if err := os.WriteFile(mdPath, []byte("# hi"), 0o644); err != nil {
		t.Fatal(err)
	}
	token, err := service.RegisterFileTab(mdPath)
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "/import?target=file&token="+token+"&name=shot.png", bytes.NewReader(testPNG))
	req.Header.Set("Content-Type", "image/png")
	recorder := httptest.NewRecorder()
	service.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s", recorder.Code, recorder.Body.String())
	}
	var result ImportResult
	if err := json.Unmarshal(recorder.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(result.Ref, "./note.assets/") {
		t.Fatalf("ref = %q", result.Ref)
	}
	if _, err := os.Stat(filepath.Join(dir, "note.assets")); err != nil {
		t.Fatalf("assets dir: %v", err)
	}
}

func TestSaveLocalAndServe(t *testing.T) {
	service, _ := newTestService(t)
	if _, err := service.saveLocalStream(bytes.NewReader(testPNG), "shot.png", imageType{MimeType: "image/png", Ext: "png"}); err != nil {
		t.Fatalf("save local: %v", err)
	}
	id, err := service.db.ListLocalAttachmentIDs()
	if err != nil || len(id) != 1 {
		t.Fatalf("list attachments: %v %v", id, err)
	}
	req := httptest.NewRequest(http.MethodGet, "/local-media/"+id[0], nil)
	recorder := httptest.NewRecorder()
	service.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d", recorder.Code)
	}
	if got := recorder.Header().Get("Content-Type"); got != "image/png" {
		t.Fatalf("content type = %q", got)
	}
	if !bytes.Equal(recorder.Body.Bytes(), testPNG) {
		t.Fatal("body mismatch")
	}
}

func TestFileTokenAndTraversal(t *testing.T) {
	service, _ := newTestService(t)
	dir := t.TempDir()
	mdPath := filepath.Join(dir, "note.md")
	if err := os.WriteFile(mdPath, []byte("# hi"), 0o644); err != nil {
		t.Fatal(err)
	}
	token, err := service.RegisterFileTab(mdPath)
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	root, ok := service.rootForToken(token)
	if !ok {
		t.Fatal("token missing")
	}
	ref, _, err := service.saveFileStream(root, token, bytes.NewReader(testPNG), "shot.png", imageType{MimeType: "image/png", Ext: "png"})
	if err != nil {
		t.Fatalf("save file: %v", err)
	}
	rel := strings.TrimPrefix(ref, "./")
	req := httptest.NewRequest(http.MethodGet, "/file/"+token+"/"+rel, nil)
	recorder := httptest.NewRecorder()
	service.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d", recorder.Code)
	}

	traversal := httptest.NewRequest(http.MethodGet, "/file/"+token+"/../outside.png", nil)
	recorder = httptest.NewRecorder()
	service.ServeHTTP(recorder, traversal)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("traversal status = %d", recorder.Code)
	}
}

func TestRewriteFileRefsToLocalAndExport(t *testing.T) {
	service, _ := newTestService(t)
	dir := t.TempDir()
	mdPath := filepath.Join(dir, "note.md")
	assets := filepath.Join(dir, "note.assets")
	if err := os.MkdirAll(assets, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(assets, "a.png"), testPNG, 0o644); err != nil {
		t.Fatal(err)
	}
	content := "# hi\n\n![a](./note.assets/a.png)\n"
	rewritten, err := service.RewriteFileRefsToLocal(content, mdPath)
	if err != nil {
		t.Fatalf("rewrite local: %v", err)
	}
	if !strings.Contains(rewritten, "local-media/") {
		t.Fatalf("unexpected content: %s", rewritten)
	}

	target := filepath.Join(t.TempDir(), "export.md")
	exported, err := service.RewriteLocalRefsToExport(rewritten, target)
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if !strings.Contains(exported, "./export.assets/") {
		t.Fatalf("unexpected export: %s", exported)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(target), "export.assets")); err != nil {
		t.Fatalf("assets dir: %v", err)
	}
}

func TestUploadServerStream(t *testing.T) {
	var gotID string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/desktop/media" {
			t.Errorf("path = %s", r.URL.Path)
		}
		if err := r.ParseMultipartForm(2 << 20); err != nil {
			t.Errorf("parse multipart: %v", err)
		}
		if _, _, err := r.FormFile("file"); err != nil {
			t.Errorf("file part: %v", err)
		}
		gotID = "att-1"
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"id": gotID, "url": "/api/media/att-1"})
	}))
	defer server.Close()

	db, err := store.OpenAt(filepath.Join(t.TempDir(), "desktop.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := db.SetToken("token"); err != nil {
		t.Fatal(err)
	}
	service := New(db, api.New(db, server.URL+"/api"))
	attachment, err := service.uploadServerStream(bytes.NewReader(testPNG), "a.png", imageType{MimeType: "image/png", Ext: "png"})
	if err != nil {
		t.Fatalf("upload: %v", err)
	}
	if attachment.ID != gotID {
		t.Fatalf("id = %q", attachment.ID)
	}
}
