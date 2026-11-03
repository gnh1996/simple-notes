package notes

import "testing"

func TestLocalFolderTreeListsSubtreesAndCounts(t *testing.T) {
	svc := openTestNotes(t)
	// 两棵树，其中两个子文件夹同名。
	for _, folder := range []struct {
		id, parent, name string
	}{
		{"lf1", "", "工作"},
		{"lf2", "lf1", "项目A"},
		{"lf3", "", "生活"},
		{"lf4", "lf3", "项目A"},
	} {
		if err := svc.db.SaveLocalFolder(folder.id, folder.name, 0, folder.parent); err != nil {
			t.Fatalf("创建本地文件夹 %s: %v", folder.id, err)
		}
	}

	folders, err := svc.Folders()
	if err != nil {
		t.Fatal(err)
	}
	if len(folders) != 6 {
		t.Fatalf("folders = %+v", folders)
	}
	for _, folder := range folders {
		switch folder.ID {
		case "lf2":
			if folder.ParentID != "lf1" || folder.Name != "项目A" || !folder.Local {
				t.Fatalf("lf2 = %+v", folder)
			}
		case "lf4":
			if folder.ParentID != "lf3" || folder.Name != "项目A" {
				t.Fatalf("lf4 = %+v", folder)
			}
		}
	}

	// 纳入本地文件夹
	first, err := svc.ImportFile(ImportInput{Title: "工作项", Content: "a", Target: "local", FolderID: "lf2"})
	if err != nil || first == nil || first.Note == nil || first.Note.FolderID != "lf2" {
		t.Fatalf("import lf2 = %+v, err=%v", first, err)
	}
	second, err := svc.ImportFile(ImportInput{Title: "生活项", Content: "b", Target: "local", FolderID: "lf4"})
	if err != nil || second == nil || second.Note == nil || second.Note.FolderID != "lf4" {
		t.Fatalf("import lf4 = %+v, err=%v", second, err)
	}

	// 选中父文件夹要包含子孙，且不会串到另一棵树。
	work, err := svc.List("NOTE", "lf1", "")
	if err != nil || len(work) != 1 || work[0].ID != first.Note.ID {
		t.Fatalf("lf1 子树 = %+v, err=%v", work, err)
	}
	life, err := svc.List("NOTE", "lf3", "")
	if err != nil || len(life) != 1 || life[0].ID != second.Note.ID {
		t.Fatalf("lf3 子树 = %+v, err=%v", life, err)
	}
	leaf, err := svc.List("NOTE", "lf2", "")
	if err != nil || len(leaf) != 1 || leaf[0].ID != first.Note.ID {
		t.Fatalf("lf2 = %+v, err=%v", leaf, err)
	}

	// 子树计数：父级 = 自身 + 子孙。
	folders, err = svc.Folders()
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]int{"lf1": 1, "lf2": 1, "lf3": 1, "lf4": 1}
	for _, folder := range folders {
		if count, ok := want[folder.ID]; ok && folder.NoteCount != count {
			t.Fatalf("%s 子树数量 = %d, want %d", folder.ID, folder.NoteCount, count)
		}
	}
}

func TestImportFileLocalDefaultsToUncategorized(t *testing.T) {
	svc := openTestNotes(t)
	result, err := svc.ImportFile(ImportInput{Title: "默认", Content: "x", Target: "local"})
	if err != nil || result == nil || result.Note == nil {
		t.Fatalf("import = %+v, err=%v", result, err)
	}
	if result.Note.FolderID != "" {
		t.Fatalf("未指定文件夹时应为未分类，实际 %q", result.Note.FolderID)
	}
}

func TestNormalizeFolderIDRejectsCrossScopeFolder(t *testing.T) {
	svc := openTestNotes(t)
	if err := svc.db.SaveLocalFolder("lf1", "工作", 0, ""); err != nil {
		t.Fatalf("创建本地文件夹: %v", err)
	}
	if got := svc.normalizeFolderID("local", "lf1"); got != "lf1" {
		t.Fatalf("本地文件夹应保留，got %q", got)
	}
	if got := svc.normalizeFolderID("srv:x", "lf1"); got != "" {
		t.Fatalf("跨 scope 的文件夹应落未分类，got %q", got)
	}
	if got := svc.normalizeFolderID("local", "missing"); got != "" {
		t.Fatalf("未知文件夹应落未分类，got %q", got)
	}
	if got := svc.normalizeFolderID("local", UncategorizedFolderID); got != "" {
		t.Fatalf("未分类应落空，got %q", got)
	}
}
