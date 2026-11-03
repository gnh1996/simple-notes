package folders

import (
	"path/filepath"
	"testing"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/store"
)

func openTestFolders(t *testing.T) (*Service, *store.Store) {
	t.Helper()
	db, err := store.OpenAt(filepath.Join(t.TempDir(), "desktop.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return New(db, api.New(db, "http://127.0.0.1:1/api")), db
}

func TestLocalFolderCreateRenameDeleteOffline(t *testing.T) {
	svc, db := openTestFolders(t)

	created, err := svc.Create("工作", "local")
	if err != nil || created == nil || created.State != "ok" || created.Folder == nil {
		t.Fatalf("create = %+v, err=%v", created, err)
	}
	if !created.Folder.Local || created.Folder.Name != "工作" {
		t.Fatalf("本地文件夹标记错误: %+v", created.Folder)
	}
	id := created.Folder.ID

	dup, err := svc.Create("工作", "local")
	if err != nil || dup == nil || dup.State != "error" {
		t.Fatalf("同名应被拒绝: %+v, err=%v", dup, err)
	}
	reserved, err := svc.Create("本地文件", "local")
	if err != nil || reserved == nil || reserved.State != "error" {
		t.Fatalf("内置名称应被拒绝: %+v, err=%v", reserved, err)
	}

	renamed, err := svc.Rename(id, "生活")
	if err != nil || renamed == nil || renamed.State != "ok" {
		t.Fatalf("rename = %+v, err=%v", renamed, err)
	}
	conflict, err := svc.Rename(id, "本地文件")
	if err != nil || conflict == nil || conflict.State != "error" {
		t.Fatalf("改名到内置名称应被拒绝: %+v, err=%v", conflict, err)
	}

	row := store.NoteRow{
		ID: "n1", Kind: "NOTE", Title: "笔记", Content: "正文",
		FolderID: id, ContentLoaded: true, UpdatedAt: 1, Tags: []string{},
	}
	if err := db.InsertNoteLocal("local", row, `[]`, "hash"); err != nil {
		t.Fatalf("写入本地笔记: %v", err)
	}

	deleted, err := svc.Delete(id)
	if err != nil || deleted == nil || deleted.State != "ok" {
		t.Fatalf("delete = %+v, err=%v", deleted, err)
	}
	got, ok, err := db.GetNote("local", "n1")
	if err != nil || !ok || got.FolderID != "" {
		t.Fatalf("删除后笔记应落未分类: %+v, ok=%v err=%v", got, ok, err)
	}
}

func TestFolderWritesRequireLoginForServerTarget(t *testing.T) {
	svc, _ := openTestFolders(t)

	if _, err := svc.Create("工作", "server"); err == nil {
		t.Fatal("未登录不能新建服务端文件夹")
	}
	if _, err := svc.Rename("remote-id", "工作"); err == nil {
		t.Fatal("未登录不能改名服务端文件夹")
	}
	if _, err := svc.Delete("remote-id"); err == nil {
		t.Fatal("未登录不能删除服务端文件夹")
	}
}
