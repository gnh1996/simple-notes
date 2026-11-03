package store

import "testing"

func TestLocalFolderNameUniquePerScope(t *testing.T) {
	db := openTestStore(t)

	if err := db.SaveLocalFolder("f1", "工作", 0); err != nil {
		t.Fatalf("创建本地文件夹: %v", err)
	}
	if err := db.SaveLocalFolder("f2", "工作", 0); err == nil {
		t.Fatal("同一 scope 内同名文件夹应被唯一索引拒绝")
	}
	if err := db.SaveLocalFolder("f2", "生活", 0); err != nil {
		t.Fatalf("不同名应成功: %v", err)
	}

	// 保留名「本地文件」不可被自建文件夹占用。
	taken, err := db.LocalFolderNameTaken("本地文件", "")
	if err != nil || !taken {
		t.Fatalf("内置名称应视为已占用: taken=%v err=%v", taken, err)
	}
	taken, err = db.LocalFolderNameTaken(" 工作 ", "f1")
	if err != nil || taken {
		t.Fatalf("改名时排除自身: taken=%v err=%v", taken, err)
	}
	taken, err = db.LocalFolderNameTaken("工作", "")
	if err != nil || !taken {
		t.Fatalf("同名应判为占用: taken=%v err=%v", taken, err)
	}
}

func TestDeleteLocalFolderMovesNotesToUncategorized(t *testing.T) {
	db := openTestStore(t)
	if err := db.SaveLocalFolder("f1", "工作", 0); err != nil {
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

func TestLocalFolderNameIndexOnlyCoversLocalScope(t *testing.T) {
	db := openTestStore(t)
	if err := db.SaveLocalFolder("f1", "工作", 0); err != nil {
		t.Fatalf("创建本地文件夹: %v", err)
	}

	// 服务端镜像与本地文件夹同名：部分索引不应拦截（跨 scope 同名允许）。
	if err := db.UpsertRemoteFolders("srv:a", []FolderRow{{ID: "r1", Name: "工作"}}); err != nil {
		t.Fatalf("服务端镜像同名不应失败: %v", err)
	}
	// 不同账号之间同名同样允许。
	if err := db.UpsertRemoteFolders("srv:b", []FolderRow{{ID: "r2", Name: "工作"}}); err != nil {
		t.Fatalf("跨账号同名不应失败: %v", err)
	}
}

func TestLocalFolderExistsCoversBuiltin(t *testing.T) {
	db := openTestStore(t)
	exists, err := db.LocalFolderExists(LocalFilesFolderID)
	if err != nil || !exists {
		t.Fatalf("内置「本地文件」应始终存在: exists=%v err=%v", exists, err)
	}
	exists, err = db.LocalFolderExists("missing")
	if err != nil || exists {
		t.Fatalf("不存在的文件夹: exists=%v err=%v", exists, err)
	}
	exists, err = db.LocalFolderExists(UncategorizedFolderID)
	if err != nil || exists {
		t.Fatalf("未分类不是文件夹: exists=%v err=%v", exists, err)
	}
}
