package notes

import "testing"

func TestLocalFoldersAreListedAndUsable(t *testing.T) {
	svc := openTestNotes(t)
	if err := svc.db.SaveLocalFolder("lf1", "工作", 0); err != nil {
		t.Fatalf("创建本地文件夹: %v", err)
	}

	// 空文件夹也要出现在列表里（不依赖笔记数量）。
	folders, err := svc.Folders()
	if err != nil {
		t.Fatal(err)
	}
	var builtin, custom bool
	for _, folder := range folders {
		switch folder.ID {
		case LocalFilesFolderID:
			builtin = folder.Local && folder.NoteCount == 0
		case "lf1":
			custom = folder.Local && folder.Name == "工作" && folder.NoteCount == 0
		}
	}
	if !builtin || !custom {
		t.Fatalf("本地内置 / 自建文件夹缺失: %+v", folders)
	}

	// 纳入管理时可以直接归入本地自建文件夹。
	result, err := svc.ImportFile(ImportInput{
		Title: "工作中", Content: "正文", Target: "local", FolderID: "lf1",
	})
	if err != nil || result == nil || result.Note == nil {
		t.Fatalf("import = %+v, err=%v", result, err)
	}
	if result.Note.FolderID != "lf1" || !result.Note.LocalOnly {
		t.Fatalf("导入归类错误: %+v", result.Note)
	}

	rows, err := svc.List("NOTE", "lf1", "")
	if err != nil || len(rows) != 1 || rows[0].ID != result.Note.ID {
		t.Fatalf("按本地文件夹筛选 = %+v, err=%v", rows, err)
	}
	empty, err := svc.List("NOTE", LocalFilesFolderID, "")
	if err != nil || len(empty) != 0 {
		t.Fatalf("「本地文件」应为空: %+v, err=%v", empty, err)
	}

	// 归类到不存在的文件夹要收敛为未分类，而不是把脏 id 写进本地库。
	saved, err := svc.Save(SaveInput{
		ID: result.Note.ID, Title: "工作中", Content: "正文", FolderID: "missing",
	})
	if err != nil || saved == nil || saved.Note == nil {
		t.Fatalf("save = %+v, err=%v", saved, err)
	}
	if saved.Note.FolderID != "" {
		t.Fatalf("未知文件夹应落未分类，实际 %q", saved.Note.FolderID)
	}
	uncategorized, err := svc.List("NOTE", UncategorizedFolderID, "")
	if err != nil || len(uncategorized) != 1 {
		t.Fatalf("未分类 = %+v, err=%v", uncategorized, err)
	}
}

func TestImportFileLocalDefaultsToLocalFiles(t *testing.T) {
	svc := openTestNotes(t)
	result, err := svc.ImportFile(ImportInput{Title: "默认", Content: "x", Target: "local"})
	if err != nil || result == nil || result.Note == nil {
		t.Fatalf("import = %+v, err=%v", result, err)
	}
	if result.Note.FolderID != LocalFilesFolderID {
		t.Fatalf("未指定文件夹时应归入「本地文件」，实际 %q", result.Note.FolderID)
	}
}
