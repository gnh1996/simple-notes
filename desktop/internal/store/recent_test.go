package store

import (
	"fmt"
	"testing"
)

func TestRecentFilesLifecycle(t *testing.T) {
	db := openTestStore(t)

	if err := db.UpsertRecentFile("/tmp/a.md", "a.md", 1000); err != nil {
		t.Fatal(err)
	}
	if err := db.UpsertRecentFile("/tmp/b.md", "b.md", 2000); err != nil {
		t.Fatal(err)
	}
	items, err := db.ListRecentFiles(20)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 2 || items[0].Path != "/tmp/b.md" {
		t.Fatalf("recent = %+v", items)
	}

	// 同一个路径只保留一条，并更新时间。
	if err := db.UpsertRecentFile("/tmp/a.md", "a.md", 3000); err != nil {
		t.Fatal(err)
	}
	items, _ = db.ListRecentFiles(20)
	if len(items) != 2 || items[0].Path != "/tmp/a.md" {
		t.Fatalf("recent = %+v", items)
	}

	if err := db.DeleteRecentFile("/tmp/a.md"); err != nil {
		t.Fatal(err)
	}
	items, _ = db.ListRecentFiles(20)
	if len(items) != 1 {
		t.Fatalf("recent = %+v", items)
	}
	if err := db.ClearRecentFiles(); err != nil {
		t.Fatal(err)
	}
	items, _ = db.ListRecentFiles(20)
	if len(items) != 0 {
		t.Fatalf("recent 未清空: %+v", items)
	}
}

func TestRecentFilesKeepsLatestTwenty(t *testing.T) {
	db := openTestStore(t)
	for i := 1; i <= 25; i++ {
		if err := db.UpsertRecentFile(fmt.Sprintf("/tmp/%02d.md", i), "x.md", int64(i)); err != nil {
			t.Fatal(err)
		}
	}
	items, err := db.ListRecentFiles(20)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 20 {
		t.Fatalf("len = %d", len(items))
	}
	if items[0].Path != "/tmp/25.md" || items[19].Path != "/tmp/06.md" {
		t.Fatalf("裁剪顺序错误: first=%s last=%s", items[0].Path, items[19].Path)
	}
}

func TestMoveNoteScopeAndHasNoteInFolder(t *testing.T) {
	db := openTestStore(t)
	row := NoteRow{ID: "n1", Kind: "NOTE", Title: "t", Content: "c", ContentLoaded: true}
	if err := db.InsertNoteLocal("local", row, `[]`, "h"); err != nil {
		t.Fatal(err)
	}
	ok, err := db.HasNoteInFolder("local", "local-files")
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Fatal("folder_id 为空时不应命中 local-files")
	}
	if err := db.MoveNoteScope("local", "srv:s", "n1", ""); err != nil {
		t.Fatal(err)
	}
	if _, found, _ := db.GetNote("local", "n1"); found {
		t.Fatal("迁移后不应仍在 local")
	}
	moved, found, err := db.GetNote("srv:s", "n1")
	if err != nil || !found {
		t.Fatalf("moved: found=%v err=%v", found, err)
	}
	if !moved.Dirty {
		t.Fatal("迁移后应标记 dirty")
	}
}
