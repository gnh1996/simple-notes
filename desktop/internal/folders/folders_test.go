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

func TestLocalFolderTreeCreateRenameDelete(t *testing.T) {
	svc, db := openTestFolders(t)

	created, err := svc.Create("工作", "local", "")
	if err != nil || created == nil || created.State != "ok" || created.Folder == nil {
		t.Fatalf("create = %+v, err=%v", created, err)
	}
	if !created.Folder.Local || created.Folder.ParentID != "" || created.Folder.Name != "工作" {
		t.Fatalf("根文件夹状态错误: %+v", created.Folder)
	}
	rootID := created.Folder.ID

	child, err := svc.Create("项目A", "local", rootID)
	if err != nil || child == nil || child.State != "ok" || child.Folder == nil {
		t.Fatalf("create child = %+v, err=%v", child, err)
	}
	if child.Folder.ParentID != rootID {
		t.Fatalf("子文件夹父级错误: %+v", child.Folder)
	}

	// 同一父级下重名拒绝。
	dup, err := svc.Create("项目A", "local", rootID)
	if err != nil || dup == nil || dup.State != "error" {
		t.Fatalf("同父级重名应拒绝: %+v, err=%v", dup, err)
	}
	// 不同父级允许同名。
	if other, err := svc.Create("项目A", "local", ""); err != nil || other == nil || other.State != "ok" {
		t.Fatalf("跨层级同名应允许: %+v, err=%v", other, err)
	}

	renamed, err := svc.Rename(child.Folder.ID, "项目B")
	if err != nil || renamed == nil || renamed.State != "ok" {
		t.Fatalf("rename = %+v, err=%v", renamed, err)
	}

	// 有子文件夹时禁止删除。
	if deleted, err := svc.Delete(rootID); err != nil || deleted == nil || deleted.State != "error" {
		t.Fatalf("父文件夹应拒绝删除: %+v, err=%v", deleted, err)
	}

	row := store.NoteRow{
		ID: "n1", Kind: "NOTE", Title: "笔记", Content: "正文",
		FolderID: child.Folder.ID, ContentLoaded: true, UpdatedAt: 1, Tags: []string{},
	}
	if err := db.InsertNoteLocal("local", row, `[]`, "hash"); err != nil {
		t.Fatalf("写入本地笔记: %v", err)
	}
	if deleted, err := svc.Delete(child.Folder.ID); err != nil || deleted == nil || deleted.State != "ok" {
		t.Fatalf("删除叶子文件夹: %+v, err=%v", deleted, err)
	}
	got, ok, err := db.GetNote("local", "n1")
	if err != nil || !ok || got.FolderID != "" {
		t.Fatalf("删除后笔记应落未分类: %+v, ok=%v err=%v", got, ok, err)
	}
}

func TestLocalFolderDepthLimit(t *testing.T) {
	svc, _ := openTestFolders(t)
	parent := ""
	for level := 1; level <= 5; level++ {
		created, err := svc.Create("L"+string(rune('0'+level)), "local", parent)
		if err != nil || created == nil || created.State != "ok" || created.Folder == nil {
			t.Fatalf("第 %d 级创建失败: %+v, err=%v", level, created, err)
		}
		parent = created.Folder.ID
	}
	tooDeep, err := svc.Create("L6", "local", parent)
	if err == nil {
		t.Fatalf("第 6 级应被拒绝: %+v", tooDeep)
	}
}

func TestFolderWritesRequireLoginForCloudTarget(t *testing.T) {
	svc, _ := openTestFolders(t)

	if _, err := svc.Create("工作", "server", ""); err == nil {
		t.Fatal("未登录不能新建云端文件夹")
	}
	if _, err := svc.Rename("remote-id", "工作"); err == nil {
		t.Fatal("未登录不能改名云端文件夹")
	}
	if _, err := svc.Delete("remote-id"); err == nil {
		t.Fatal("未登录不能删除云端文件夹")
	}
}
