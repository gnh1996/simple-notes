package store

import "testing"

func TestLocalFolderNameUniquePerParent(t *testing.T) {
	db := openTestStore(t)

	if err := db.SaveLocalFolder("f1", "工作", 0, ""); err != nil {
		t.Fatalf("创建本地文件夹: %v", err)
	}
	if err := db.SaveLocalFolder("f2", "工作", 0, ""); err == nil {
		t.Fatal("同一父级下同名文件夹应被唯一索引拒绝")
	}
	if err := db.SaveLocalFolder("f2", "生活", 0, ""); err != nil {
		t.Fatalf("不同名应成功: %v", err)
	}

	// 嵌套：不同父级下允许同名。
	if err := db.SaveLocalFolder("c1", "工作", 0, "f1"); err != nil {
		t.Fatalf("子文件夹同名应成功: %v", err)
	}
	if err := db.SaveLocalFolder("c2", "工作", 0, "f2"); err != nil {
		t.Fatalf("另一父级下同名应成功: %v", err)
	}

	taken, err := db.LocalFolderNameTaken(" 工作 ", "", "f1")
	if err != nil || taken {
		t.Fatalf("改名时排除自身: taken=%v err=%v", taken, err)
	}
	taken, err = db.LocalFolderNameTaken("工作", "", "")
	if err != nil || !taken {
		t.Fatalf("根级同名应判为占用: taken=%v err=%v", taken, err)
	}
	taken, err = db.LocalFolderNameTaken("工作", "f1", "")
	if err != nil || !taken {
		t.Fatalf("子级同名应判为占用: taken=%v err=%v", taken, err)
	}
}

func TestDeleteLocalFolderMovesNotesToUncategorized(t *testing.T) {
	db := openTestStore(t)
	if err := db.SaveLocalFolder("f1", "工作", 0, ""); err != nil {
		t.Fatalf("创建本地文件夹: %v", err)
	}
	row := NoteRow{
		ID: "n1", Kind: "NOTE", Title: "笔记", Content: "正文",
		FolderID: "f1", ContentLoaded: true, UpdatedAt: 1, Tags: []string{},
	}
	if err := db.InsertNoteLocal("local", row, `[]`, "hash"); err != nil {
		t.Fatalf("写入本地笔记: %v", err)
	}

	if err := db.DeleteLocalFolder("f1"); err != nil {
		t.Fatalf("删除本地文件夹: %v", err)
	}

	got, ok, err := db.GetNote("local", "n1")
	if err != nil || !ok {
		t.Fatalf("读取笔记: ok=%v err=%v", ok, err)
	}
	if got.FolderID != "" {
		t.Fatalf("删除文件夹后笔记应落到未分类，实际 folder_id=%q", got.FolderID)
	}
	if _, exists, err := db.GetFolder("local", "f1"); err != nil || exists {
		t.Fatalf("文件夹应被删除: exists=%v err=%v", exists, err)
	}
}

func TestFolderNameIndexOnlyCoversLocalScope(t *testing.T) {
	db := openTestStore(t)
	if err := db.SaveLocalFolder("f1", "工作", 0, ""); err != nil {
		t.Fatalf("创建本地文件夹: %v", err)
	}

	// 云端镜像与本地文件夹同名：部分索引不应拦截（跨 scope 同名允许）。
	if err := db.UpsertRemoteFolders("srv:a", []FolderRow{{ID: "r1", Name: "工作"}}); err != nil {
		t.Fatalf("云端镜像同名不应失败: %v", err)
	}
	// 不同账号之间同名同样允许。
	if err := db.UpsertRemoteFolders("srv:b", []FolderRow{{ID: "r2", Name: "工作"}}); err != nil {
		t.Fatalf("跨账号同名不应失败: %v", err)
	}
	// 云端同一账号同一父级下同名由服务端保证，本地镜像允许短暂重复。
	if err := db.UpsertRemoteFolders("srv:a", []FolderRow{{ID: "r3", ParentID: "r1", Name: "工作"}}); err != nil {
		t.Fatalf("云端不同父级同名不应失败: %v", err)
	}
}

func TestListFoldersHandlesNullParent(t *testing.T) {
	db := openTestStore(t)
	// 0005 之前的老行 parent_id 为 NULL（迁移只加列，不回填）。
	if _, err := db.db.Exec(`
		INSERT INTO folder_local (scope_id, id, parent_id, name, sort, create_time, dirty, updated_at)
		VALUES ('local', 'legacy', NULL, '旧文件夹', 0, 1, 0, 1)
	`); err != nil {
		t.Fatalf("写入老文件夹: %v", err)
	}
	folders, err := db.ListFolders("local")
	if err != nil {
		t.Fatalf("ListFolders: %v", err)
	}
	if len(folders) != 1 || folders[0].ParentID != "" {
		t.Fatalf("NULL 父级应规范化为空串: %+v", folders)
	}
}

func TestFolderExistsAndChildren(t *testing.T) {
	db := openTestStore(t)
	if err := db.SaveLocalFolder("f1", "工作", 0, ""); err != nil {
		t.Fatalf("创建本地文件夹: %v", err)
	}
	if err := db.SaveLocalFolder("c1", "项目", 0, "f1"); err != nil {
		t.Fatalf("创建子文件夹: %v", err)
	}

	exists, err := db.FolderExists("local", "f1")
	if err != nil || !exists {
		t.Fatalf("本地文件夹应存在: exists=%v err=%v", exists, err)
	}
	exists, err = db.FolderExists("local", "missing")
	if err != nil || exists {
		t.Fatalf("不存在的文件夹: exists=%v err=%v", exists, err)
	}
	exists, err = db.FolderExists("local", UncategorizedFolderID)
	if err != nil || exists {
		t.Fatalf("未分类不是文件夹: exists=%v err=%v", exists, err)
	}

	hasChildren, err := db.FolderHasChildren("local", "f1")
	if err != nil || !hasChildren {
		t.Fatalf("f1 应有子级: hasChildren=%v err=%v", hasChildren, err)
	}
	hasChildren, err = db.FolderHasChildren("local", "c1")
	if err != nil || hasChildren {
		t.Fatalf("c1 不应有子级: hasChildren=%v err=%v", hasChildren, err)
	}
}
