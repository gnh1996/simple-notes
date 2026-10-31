package store

import (
	"path/filepath"
	"testing"
)

func openTestStore(t *testing.T) *Store {
	t.Helper()
	db, err := OpenAt(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func TestApplyRemoteNotesCreatesStubAndPreservesContent(t *testing.T) {
	db := openTestStore(t)
	scope := Scope{ScopeID: "srv:x:user:u", ServerURL: "http://x/api", UserID: "u", Username: "u"}
	if err := db.SaveScope(scope); err != nil {
		t.Fatalf("save scope: %v", err)
	}

	if err := db.ApplyRemoteNotes(scope.ScopeID, []RemoteNote{{
		ID: "n1", Kind: "NOTE", Title: "第一版", Version: 1, UpdatedAt: 1000,
	}}); err != nil {
		t.Fatalf("apply manifest: %v", err)
	}

	row, ok, err := db.GetNote(scope.ScopeID, "n1")
	if err != nil || !ok {
		t.Fatalf("get note: ok=%v err=%v", ok, err)
	}
	if row.ContentLoaded {
		t.Fatal("存根不应预加载正文")
	}
	if row.RemoteVersion != 1 {
		t.Fatalf("remote_version = %d", row.RemoteVersion)
	}

	row.Content = "正文"
	row.ContentLoaded = true
	if err := db.SaveNoteDetail(scope.ScopeID, row, `[]`, "hash"); err != nil {
		t.Fatalf("save detail: %v", err)
	}

	// 远端只改标题：正文缓存与 base_version 必须保留。
	if err := db.ApplyRemoteNotes(scope.ScopeID, []RemoteNote{{
		ID: "n1", Kind: "NOTE", Title: "第二版", Version: 2, UpdatedAt: 2000,
	}}); err != nil {
		t.Fatalf("re-apply manifest: %v", err)
	}
	row, _, err = db.GetNote(scope.ScopeID, "n1")
	if err != nil {
		t.Fatal(err)
	}
	if row.Title != "第二版" || row.Content != "正文" || !row.ContentLoaded {
		t.Fatalf("缓存被破坏: %+v", row)
	}
	if row.BaseVersion != 1 || row.RemoteVersion != 2 {
		t.Fatalf("版本基线错误: base=%d remote=%d", row.BaseVersion, row.RemoteVersion)
	}
}

func TestApplyRemoteNotesMarksTombstone(t *testing.T) {
	db := openTestStore(t)
	scope := Scope{ScopeID: "s", ServerURL: "http://x/api", UserID: "u", Username: "u"}
	if err := db.SaveScope(scope); err != nil {
		t.Fatal(err)
	}
	_ = db.ApplyRemoteNotes(scope.ScopeID, []RemoteNote{{ID: "n1", Kind: "NOTE", Title: "a", Version: 1}})
	if err := db.ApplyRemoteNotes(scope.ScopeID, []RemoteNote{{
		ID: "n1", Kind: "NOTE", Title: "a", Version: 2, Deleted: true,
	}}); err != nil {
		t.Fatal(err)
	}

	rows, err := db.ListNotes(scope.ScopeID, "NOTE", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 0 {
		t.Fatalf("墓碑不应出现在列表: %+v", rows)
	}
}

func TestRemoteDeleteKeepsLocalDirtyRow(t *testing.T) {
	db := openTestStore(t)
	scope := Scope{ScopeID: "s", ServerURL: "http://x/api", UserID: "u", Username: "u"}
	if err := db.SaveScope(scope); err != nil {
		t.Fatal(err)
	}
	_ = db.ApplyRemoteNotes(scope.ScopeID, []RemoteNote{{ID: "n1", Kind: "NOTE", Title: "a", Version: 1}})
	row, _, _ := db.GetNote(scope.ScopeID, "n1")
	row.Content = "本地修改"
	_ = db.SaveNoteLocal(scope.ScopeID, row, `[]`, "h")

	if err := db.ApplyRemoteNotes(scope.ScopeID, []RemoteNote{{
		ID: "n1", Kind: "NOTE", Title: "a", Version: 2, Deleted: true,
	}}); err != nil {
		t.Fatal(err)
	}
	rows, err := db.ListNotes(scope.ScopeID, "NOTE", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || !rows[0].RemoteDeleted || !rows[0].Dirty {
		t.Fatalf("远端删除 + 本地修改应保留为冲突行: %+v", rows)
	}
}

func TestOutboxLifecycle(t *testing.T) {
	db := openTestStore(t)
	if err := db.Enqueue("s", "note", "n1", "delete", `{"kind":"NOTE"}`); err != nil {
		t.Fatal(err)
	}
	items, err := db.PendingOutbox("s")
	if err != nil || len(items) != 1 {
		t.Fatalf("pending: %v %v", items, err)
	}
	if err := db.BumpOutbox("s", "note", "n1"); err != nil {
		t.Fatal(err)
	}
	items, _ = db.PendingOutbox("s")
	if items[0].RetryCount != 1 || items[0].NextRetryAt == 0 {
		t.Fatalf("退避未生效: %+v", items[0])
	}
	if err := db.RemoveOutbox("s", "note", "n1", "delete"); err != nil {
		t.Fatal(err)
	}
	items, _ = db.PendingOutbox("s")
	if len(items) != 0 {
		t.Fatalf("outbox 未清空: %+v", items)
	}
}

func TestRemoveOutboxKeepsNewerOp(t *testing.T) {
	db := openTestStore(t)
	if err := db.Enqueue("s", "note", "n1", "create", `{}`); err != nil {
		t.Fatal(err)
	}
	if err := db.Enqueue("s", "note", "n1", "update", `{}`); err != nil {
		t.Fatal(err)
	}
	if err := db.RemoveOutbox("s", "note", "n1", "create"); err != nil {
		t.Fatal(err)
	}
	items, _ := db.PendingOutbox("s")
	if len(items) != 1 || items[0].Op != "update" {
		t.Fatalf("create 成功后不应删掉新入队的 update: %+v", items)
	}
	if err := db.RemoveOutbox("s", "note", "n1", "update"); err != nil {
		t.Fatal(err)
	}
	items, _ = db.PendingOutbox("s")
	if len(items) != 0 {
		t.Fatalf("update 应被删除: %+v", items)
	}
}

func TestUpsertRemoteFoldersPreservesDirty(t *testing.T) {
	db := openTestStore(t)
	scope := Scope{ScopeID: "s", ServerURL: "http://x/api", UserID: "u", Username: "u"}
	if err := db.SaveScope(scope); err != nil {
		t.Fatal(err)
	}
	_ = db.SaveFolderLocal(scope.ScopeID, "f1", "本地改名", 0)
	if err := db.UpsertRemoteFolders(scope.ScopeID, []FolderRow{{ID: "f1", Name: "服务端名"}}); err != nil {
		t.Fatal(err)
	}
	folders, _ := db.ListFolders(scope.ScopeID)
	if len(folders) != 1 || folders[0].Name != "本地改名" {
		t.Fatalf("dirty 文件夹不应被覆盖: %+v", folders)
	}
}

func TestSaveScopeActivatesOnlyOne(t *testing.T) {
	db := openTestStore(t)
	first := Scope{ScopeID: "a", ServerURL: "http://a/api", UserID: "u1", Username: "u1"}
	second := Scope{ScopeID: "b", ServerURL: "http://b/api", UserID: "u2", Username: "u2"}
	if err := db.SaveScope(first); err != nil {
		t.Fatal(err)
	}
	if err := db.SaveScope(second); err != nil {
		t.Fatal(err)
	}

	active, ok, err := db.ActiveScope()
	if err != nil || !ok {
		t.Fatalf("active scope: ok=%v err=%v", ok, err)
	}
	if active.ScopeID != "b" {
		t.Fatalf("active = %q", active.ScopeID)
	}
}

func TestSaveNoteLocalAndMarkSynced(t *testing.T) {
	db := openTestStore(t)
	scope := Scope{ScopeID: "s", ServerURL: "http://x/api", UserID: "u", Username: "u"}
	if err := db.SaveScope(scope); err != nil {
		t.Fatal(err)
	}
	_ = db.ApplyRemoteNotes(scope.ScopeID, []RemoteNote{{ID: "n1", Kind: "NOTE", Title: "t", Version: 1}})
	row, _, _ := db.GetNote(scope.ScopeID, "n1")
	row.Content = "旧正文"
	if err := db.SaveNoteDetail(scope.ScopeID, row, `[]`, "hash1"); err != nil {
		t.Fatal(err)
	}

	row.Content = "新正文"
	if err := db.SaveNoteLocal(scope.ScopeID, row, `[]`, "hash2"); err != nil {
		t.Fatal(err)
	}
	edited, _, _ := db.GetNote(scope.ScopeID, "n1")
	if !edited.Dirty || edited.Content != "新正文" || edited.BaseVersion != 1 {
		t.Fatalf("本地编辑状态错误: %+v", edited)
	}

	if err := db.MarkNoteSynced(scope.ScopeID, "n1", 2, "hash2"); err != nil {
		t.Fatal(err)
	}
	synced, _, _ := db.GetNote(scope.ScopeID, "n1")
	if synced.Dirty || synced.BaseVersion != 2 || synced.RemoteVersion != 2 {
		t.Fatalf("同步后状态错误: %+v", synced)
	}
}

func TestReplaceAndListFolders(t *testing.T) {
	db := openTestStore(t)
	scope := Scope{ScopeID: "s", ServerURL: "http://x/api", UserID: "u", Username: "u"}
	if err := db.SaveScope(scope); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertRemoteFolders(scope.ScopeID, []FolderRow{
		{ID: "f2", Name: "B", Sort: 2},
		{ID: "f1", Name: "A", Sort: 1},
	}); err != nil {
		t.Fatal(err)
	}
	folders, err := db.ListFolders(scope.ScopeID)
	if err != nil {
		t.Fatal(err)
	}
	if len(folders) != 2 || folders[0].Name != "A" {
		t.Fatalf("folders = %+v", folders)
	}
}
