package notes

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/store"
)

func TestContentHashNormalizesLineEndings(t *testing.T) {
	base := store.NoteRow{Title: "t", Content: "a\nb", Tags: []string{"x", "y"}}
	crlf := base
	crlf.Content = "a\r\nb"

	if ContentHash(base) != ContentHash(crlf) {
		t.Fatal("CRLF 与 LF 应得到相同 hash")
	}
}

func TestContentHashCoversSyncFields(t *testing.T) {
	base := store.NoteRow{Title: "t", Content: "a", Tags: []string{"x"}}
	changedTitle := base
	changedTitle.Title = "t2"
	changedFolder := base
	changedFolder.FolderID = "f1"
	changedPinned := base
	changedPinned.Pinned = true

	seen := map[string]bool{ContentHash(base): true}
	for _, row := range []store.NoteRow{changedTitle, changedFolder, changedPinned} {
		hash := ContentHash(row)
		if seen[hash] {
			t.Fatalf("同步字段变化应改变 hash: %+v", row)
		}
		seen[hash] = true
	}
}

func TestSortedTagNames(t *testing.T) {
	got := sortedTagNames([]tagBrief{{Name: "b"}, {Name: " a "}, {Name: ""}, {Name: "b"}})
	want := []string{"a", "b"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v, want %v", got, want)
		}
	}
}

func openTestNotes(t *testing.T) *Service {
	t.Helper()
	db, err := store.OpenAt(filepath.Join(t.TempDir(), "desktop.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return New(db, api.New(db, "http://127.0.0.1:1/api"))
}

func TestImportFileLocalWorksWithoutLogin(t *testing.T) {
	svc := openTestNotes(t)
	result, err := svc.ImportFile(ImportInput{Title: "本地导入", Content: "正文", Target: "local"})
	if err != nil {
		t.Fatal(err)
	}
	if result == nil || result.Note == nil {
		t.Fatalf("result = %+v", result)
	}
	note := result.Note
	if !note.LocalOnly || note.FolderID != LocalFilesFolderID || note.Dirty {
		t.Fatalf("本地导入状态错误: %+v", note)
	}

	rows, err := svc.List("NOTE", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].ID != note.ID || !rows[0].LocalOnly {
		t.Fatalf("列表 = %+v", rows)
	}
	filtered, err := svc.List("NOTE", LocalFilesFolderID, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(filtered) != 1 {
		t.Fatalf("本地文件分组 = %+v", filtered)
	}

	folders, err := svc.Folders()
	if err != nil {
		t.Fatal(err)
	}
	wantFolders := []struct {
		id    string
		name  string
		count int
	}{
		{"", "全部笔记", 1},
		{UncategorizedFolderID, "未分类", 0},
		{LocalFilesFolderID, "本地文件", 1},
	}
	if len(folders) != len(wantFolders) {
		t.Fatalf("folders = %+v", folders)
	}
	for i, want := range wantFolders {
		if folders[i].ID != want.id || folders[i].Name != want.name || folders[i].NoteCount != want.count {
			t.Fatalf("folders[%d] = %+v, want %+v", i, folders[i], want)
		}
	}

	saved, err := svc.Save(SaveInput{
		ID: note.ID, Title: "改名", Content: "新正文", FolderID: LocalFilesFolderID,
	})
	if err != nil || saved == nil || saved.State != "synced" {
		t.Fatalf("save = %+v, err=%v", saved, err)
	}
	deleted, err := svc.Delete(note.ID)
	if err != nil || deleted == nil || deleted.State != "deleted" {
		t.Fatalf("delete = %+v, err=%v", deleted, err)
	}
	rows, _ = svc.List("NOTE", "", "")
	if len(rows) != 0 {
		t.Fatalf("删除后仍有记录: %+v", rows)
	}
}

func TestSaveLocalNoteMovesBetweenUncategorizedAndLocalFiles(t *testing.T) {
	svc := openTestNotes(t)
	imported, err := svc.ImportFile(ImportInput{Title: "归类测试", Content: "正文", Target: "local"})
	if err != nil || imported == nil || imported.Note == nil {
		t.Fatalf("import: %+v, err=%v", imported, err)
	}

	// 移到未分类
	saved, err := svc.Save(SaveInput{
		ID: imported.Note.ID, Title: "归类测试", Content: "正文", FolderID: UncategorizedFolderID,
	})
	if err != nil || saved == nil || saved.Note == nil {
		t.Fatalf("save uncategorized: %+v, err=%v", saved, err)
	}
	if saved.Note.FolderID != "" {
		t.Fatalf("应落到未分类，got %q", saved.Note.FolderID)
	}
	rows, err := svc.List("NOTE", UncategorizedFolderID, "")
	if err != nil || len(rows) != 1 {
		t.Fatalf("未分类列表 = %+v, err=%v", rows, err)
	}

	// 再移回「本地文件」
	saved, err = svc.Save(SaveInput{
		ID: imported.Note.ID, Title: "归类测试", Content: "正文", FolderID: LocalFilesFolderID,
	})
	if err != nil || saved == nil || saved.Note == nil || saved.Note.FolderID != LocalFilesFolderID {
		t.Fatalf("save local-files: %+v, err=%v", saved, err)
	}
	folders, err := svc.Folders()
	if err != nil {
		t.Fatal(err)
	}
	for _, folder := range folders {
		if folder.ID == LocalFilesFolderID && folder.NoteCount != 1 {
			t.Fatalf("本地文件数量 = %d", folder.NoteCount)
		}
		if folder.ID == UncategorizedFolderID && folder.NoteCount != 0 {
			t.Fatalf("未分类数量 = %d", folder.NoteCount)
		}
	}
}

func TestCreateWithoutLoginWritesLocal(t *testing.T) {
	svc := openTestNotes(t)
	result, err := svc.Create(CreateInput{Kind: "POST", Title: "本地新建", Content: ""})
	if err != nil {
		t.Fatal(err)
	}
	if result == nil || result.Note == nil || !result.Note.LocalOnly {
		t.Fatalf("result = %+v", result)
	}
	if result.Note.Kind != "NOTE" {
		t.Fatalf("免登录模式应强制 NOTE，got %s", result.Note.Kind)
	}
	if result.Note.FolderID != "" {
		t.Fatalf("应用内新建应为未分类，got %q", result.Note.FolderID)
	}
}

func TestPromoteLocalMovesScopeAndCreates(t *testing.T) {
	db, err := store.OpenAt(filepath.Join(t.TempDir(), "desktop.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	if err := db.SaveScope(store.Scope{
		ScopeID: "srv:s", ServerURL: "http://s", UserID: "u", Username: "u",
	}); err != nil {
		t.Fatal(err)
	}

	svc := New(db, api.New(db, "http://unused"))
	imported, err := svc.ImportFile(ImportInput{Title: "待同步", Content: "正文", Target: "local"})
	if err != nil || imported == nil || imported.Note == nil {
		t.Fatalf("import: %+v, err=%v", imported, err)
	}

	var body map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/desktop/notes" || r.Method != http.MethodPost {
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"version":7}`))
	}))
	defer server.Close()
	svc = New(db, api.New(db, server.URL))

	result, err := svc.PromoteLocal(imported.Note.ID, "")
	if err != nil {
		t.Fatal(err)
	}
	if result == nil || result.State != "synced" || result.Note == nil {
		t.Fatalf("result = %+v", result)
	}
	if result.Note.LocalOnly || result.Note.FolderID != "" || result.Note.Dirty {
		t.Fatalf("迁移后状态错误: %+v", result.Note)
	}
	if body["id"] != imported.Note.ID {
		t.Fatalf("POST id = %v, want %s", body["id"], imported.Note.ID)
	}
	if body["folderId"] != nil {
		t.Fatalf("local-files 不应发送到服务端: %v", body["folderId"])
	}
	if _, found, _ := db.GetNote("local", imported.Note.ID); found {
		t.Fatal("迁移后不应仍在 local")
	}
	moved, found, err := db.GetNote("srv:s", imported.Note.ID)
	if err != nil || !found {
		t.Fatalf("moved: found=%v err=%v", found, err)
	}
	if moved.Dirty || moved.BaseVersion != 7 {
		t.Fatalf("同步后本地状态错误: %+v", moved)
	}
}

func TestPromoteLocalFallsBackOnServerRejection(t *testing.T) {
	db, err := store.OpenAt(filepath.Join(t.TempDir(), "desktop.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	if err := db.SaveScope(store.Scope{
		ScopeID: "srv:s", ServerURL: "http://s", UserID: "u", Username: "u",
	}); err != nil {
		t.Fatal(err)
	}

	svc := New(db, api.New(db, "http://unused"))
	imported, err := svc.ImportFile(ImportInput{Title: "会被拒绝", Content: "正文", Target: "local"})
	if err != nil || imported == nil || imported.Note == nil {
		t.Fatalf("import: %+v, err=%v", imported, err)
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"message":"bad folder"}`))
	}))
	defer server.Close()
	svc = New(db, api.New(db, server.URL))

	result, err := svc.PromoteLocal(imported.Note.ID, "")
	if err != nil {
		t.Fatal(err)
	}
	if result == nil || result.State != "error" || result.Note == nil {
		t.Fatalf("result = %+v", result)
	}
	if !result.Note.LocalOnly || result.Note.FolderID != LocalFilesFolderID {
		t.Fatalf("应回退为本地文件: %+v", result.Note)
	}
	if _, found, _ := db.GetNote("srv:s", imported.Note.ID); found {
		t.Fatal("回退后不应留在服务端 scope")
	}
	local, found, _ := db.GetNote("local", imported.Note.ID)
	if !found || local.Dirty {
		t.Fatalf("回退后的本地行错误: found=%v row=%+v", found, local)
	}
}
