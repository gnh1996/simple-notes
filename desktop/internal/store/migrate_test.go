package store

import (
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// legacySchema 复现「无版本号时代」的表结构：
//   - note_local 缺 remote_deleted；
//   - folder_local 缺 dirty / create_time；
//   - 没有 user_version（PRAGMA 默认 0）。
//
// 用 SQL 脚本而不是二进制 fixture，便于 review 与 diff（设计 §9）。
const legacySchema = `
CREATE TABLE app_kv (
	key         TEXT PRIMARY KEY,
	value       TEXT NOT NULL,
	update_time TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE auth_session (
	id          INTEGER PRIMARY KEY CHECK (id = 1),
	token       TEXT NOT NULL,
	update_time TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE sync_scope (
	scope_id    TEXT PRIMARY KEY,
	server_url  TEXT NOT NULL,
	user_id     TEXT NOT NULL,
	username    TEXT NOT NULL,
	nickname    TEXT NOT NULL DEFAULT '',
	active      INTEGER NOT NULL DEFAULT 0,
	created_at  INTEGER NOT NULL
);
CREATE TABLE note_local (
	scope_id                TEXT    NOT NULL,
	id                      TEXT    NOT NULL,
	kind                    TEXT    NOT NULL,
	title                   TEXT    NOT NULL,
	content                 TEXT,
	content_loaded          INTEGER NOT NULL DEFAULT 0,
	folder_id               TEXT,
	pinned                  INTEGER NOT NULL DEFAULT 0,
	tags_json               TEXT    NOT NULL DEFAULT '[]',
	publish_status          TEXT,
	has_unpublished_changes INTEGER,
	remote_version          INTEGER NOT NULL DEFAULT 0,
	base_version            INTEGER NOT NULL DEFAULT 0,
	local_hash              TEXT,
	base_hash               TEXT,
	dirty                   INTEGER NOT NULL DEFAULT 0,
	deleted                 INTEGER NOT NULL DEFAULT 0,
	updated_at              INTEGER NOT NULL DEFAULT 0,
	synced_at               INTEGER,
	PRIMARY KEY (scope_id, id)
);
CREATE INDEX idx_note_local_list
	ON note_local (scope_id, kind, deleted, pinned DESC, updated_at DESC);
CREATE TABLE folder_local (
	scope_id   TEXT NOT NULL,
	id         TEXT NOT NULL,
	name       TEXT NOT NULL,
	sort       INTEGER NOT NULL DEFAULT 0,
	updated_at INTEGER NOT NULL DEFAULT 0,
	PRIMARY KEY (scope_id, id)
);
CREATE TABLE outbox (
	scope_id      TEXT NOT NULL,
	entity        TEXT NOT NULL,
	id            TEXT NOT NULL,
	op            TEXT NOT NULL,
	payload       TEXT NOT NULL DEFAULT '',
	retry_count   INTEGER NOT NULL DEFAULT 0,
	next_retry_at INTEGER NOT NULL DEFAULT 0,
	created_at    INTEGER NOT NULL,
	PRIMARY KEY (scope_id, entity, id)
);
CREATE TABLE recent_file (
	path      TEXT PRIMARY KEY,
	name      TEXT NOT NULL,
	opened_at INTEGER NOT NULL
);
CREATE TABLE ui_state (
	key   TEXT PRIMARY KEY,
	value TEXT NOT NULL
);
INSERT INTO note_local (scope_id, id, kind, title, content, content_loaded, updated_at)
	VALUES ('local', 'n1', 'NOTE', '旧笔记', '正文', 1, 123);
INSERT INTO folder_local (scope_id, id, name, sort, updated_at)
	VALUES ('srv:x:user:u', 'f1', '旧文件夹', 1, 456);
`

func latestVersion() int { return migrations[len(migrations)-1].version }

func TestMigrationsRegistryValid(t *testing.T) {
	if err := validateMigrations(migrations); err != nil {
		t.Fatalf("内置迁移注册表不合法: %v", err)
	}
}

func TestValidateMigrationsRejectsBadRegistry(t *testing.T) {
	noop := func(*sql.Tx) error { return nil }
	cases := map[string][]migration{
		"empty": {},
		"gap": {
			{version: 1, name: "a", fn: noop},
			{version: 3, name: "c", fn: noop},
		},
		"duplicate": {
			{version: 1, name: "a", fn: noop},
			{version: 1, name: "b", fn: noop},
		},
		"both-file-and-fn": {
			{version: 1, name: "a", file: "0001_init.sql", fn: noop},
		},
		"neither": {
			{version: 1, name: "a"},
		},
		"missing-file": {
			{version: 1, name: "a", file: "0001_missing.sql"},
		},
		"mismatched-file": {
			{version: 1, name: "a", file: "0002_align_legacy_columns.sql"},
		},
	}
	for name, list := range cases {
		if err := validateMigrations(list); err == nil {
			t.Errorf("%s: 期望报错，实际通过", name)
		}
	}
}

func TestMigrateFreshDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "desktop.db")
	db, err := OpenAt(path)
	if err != nil {
		t.Fatalf("OpenAt: %v", err)
	}
	defer db.Close()

	version, err := db.userVersion()
	if err != nil {
		t.Fatalf("userVersion: %v", err)
	}
	if want := latestVersion(); version != want {
		t.Fatalf("user_version = %d, want %d", version, want)
	}

	for _, table := range []string{
		"app_kv", "auth_session", "sync_scope", "note_local",
		"folder_local", "outbox", "recent_file", "ui_state",
	} {
		if !hasTable(t, db.db, table) {
			t.Errorf("缺少表 %s", table)
		}
	}

	if _, err := os.Stat(path + ".bak.v0"); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("全新安装不应生成备份，stat err = %v", err)
	}
}

func TestMigrateLegacyDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "desktop.db")
	createLegacyDB(t, path)

	db, err := OpenAt(path)
	if err != nil {
		t.Fatalf("OpenAt: %v", err)
	}
	defer db.Close()

	version, err := db.userVersion()
	if err != nil {
		t.Fatalf("userVersion: %v", err)
	}
	if want := latestVersion(); version != want {
		t.Fatalf("user_version = %d, want %d", version, want)
	}

	if !hasColumn(t, db.db, "note_local", "remote_deleted") {
		t.Error("note_local 缺少 remote_deleted")
	}
	for _, column := range []string{"dirty", "create_time"} {
		if !hasColumn(t, db.db, "folder_local", column) {
			t.Errorf("folder_local 缺少 %s", column)
		}
	}

	row, ok, err := db.GetNote("local", "n1")
	if err != nil || !ok {
		t.Fatalf("老库笔记丢失: ok=%v err=%v", ok, err)
	}
	if row.Title != "旧笔记" || row.Content != "正文" {
		t.Fatalf("老库笔记内容被改动: title=%q content=%q", row.Title, row.Content)
	}

	backup := path + ".bak.v0"
	if _, err := os.Stat(backup); err != nil {
		t.Fatalf("迁移前备份不存在: %v", err)
	}
	verify, err := sql.Open("sqlite", backup)
	if err != nil {
		t.Fatalf("打开备份: %v", err)
	}
	defer verify.Close()
	if !hasTable(t, verify, "note_local") {
		t.Error("备份文件不是完整数据库")
	}
}

func TestMigrateIdempotent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "desktop.db")

	first, err := OpenAt(path)
	if err != nil {
		t.Fatalf("首次 OpenAt: %v", err)
	}
	version1, err := first.userVersion()
	if err != nil {
		t.Fatalf("userVersion: %v", err)
	}
	if err := first.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	second, err := OpenAt(path)
	if err != nil {
		t.Fatalf("二次 OpenAt: %v", err)
	}
	defer second.Close()
	version2, err := second.userVersion()
	if err != nil {
		t.Fatalf("userVersion: %v", err)
	}
	if version1 != version2 {
		t.Fatalf("重复迁移改变了版本: %d -> %d", version1, version2)
	}
}

func TestMigrateRejectsNewerSchema(t *testing.T) {
	path := filepath.Join(t.TempDir(), "desktop.db")
	db, err := OpenAt(path)
	if err != nil {
		t.Fatalf("OpenAt: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatalf("打开库: %v", err)
	}
	if _, err := raw.Exec(`PRAGMA user_version = 99`); err != nil {
		t.Fatalf("写入更高版本: %v", err)
	}
	if err := raw.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	if _, err := OpenAt(path); !errors.Is(err, ErrSchemaTooNew) {
		t.Fatalf("期望 ErrSchemaTooNew，实际 %v", err)
	}
}

func TestMigrateFailureRollsBack(t *testing.T) {
	original := migrations
	defer func() { migrations = original }()
	migrations = []migration{
		{version: 1, name: "ok", fn: func(tx *sql.Tx) error {
			_, err := tx.Exec(`CREATE TABLE t_ok (id TEXT PRIMARY KEY)`)
			return err
		}},
		{version: 2, name: "bad", fn: func(tx *sql.Tx) error {
			if _, err := tx.Exec(`CREATE TABLE t_bad (id TEXT)`); err != nil {
				return err
			}
			return errors.New("boom")
		}},
	}

	path := filepath.Join(t.TempDir(), "desktop.db")
	if _, err := OpenAt(path); err == nil {
		t.Fatal("期望迁移失败")
	}

	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatalf("打开库: %v", err)
	}
	defer raw.Close()

	var version int
	if err := raw.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil {
		t.Fatalf("读取版本: %v", err)
	}
	if version != 1 {
		t.Fatalf("失败后 user_version = %d, want 1", version)
	}
	if !hasTable(t, raw, "t_ok") {
		t.Error("已成功的迁移不应被回滚")
	}
	if hasTable(t, raw, "t_bad") {
		t.Error("失败的迁移不应留下结构")
	}
}

func TestCleanupOldBackupsKeepsNewest(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "desktop.db")
	db, err := OpenAt(path)
	if err != nil {
		t.Fatalf("OpenAt: %v", err)
	}
	defer db.Close()

	keep := path + ".bak.v2"
	old := path + ".bak.v1"
	if err := os.WriteFile(keep, []byte("keep"), 0o600); err != nil {
		t.Fatalf("写入最新备份: %v", err)
	}
	if err := os.WriteFile(old, []byte("old"), 0o600); err != nil {
		t.Fatalf("写入旧备份: %v", err)
	}

	db.cleanupOldBackups(keep)

	if _, err := os.Stat(keep); err != nil {
		t.Errorf("最新备份不应被删除: %v", err)
	}
	if _, err := os.Stat(old); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("旧备份应被删除，stat err = %v", err)
	}
}

func createLegacyDB(t *testing.T, path string) {
	t.Helper()
	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatalf("打开老库: %v", err)
	}
	defer raw.Close()
	if _, err := raw.Exec(legacySchema); err != nil {
		t.Fatalf("构造老库: %v", err)
	}
}

func hasTable(t *testing.T, db *sql.DB, name string) bool {
	t.Helper()
	var count int
	err := db.QueryRow(
		`SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?`, name,
	).Scan(&count)
	if err != nil {
		t.Fatalf("查询表 %s: %v", name, err)
	}
	return count > 0
}

func hasColumn(t *testing.T, db *sql.DB, table, column string) bool {
	t.Helper()
	rows, err := db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		t.Fatalf("读取 %s 结构: %v", table, err)
	}
	defer rows.Close()
	for rows.Next() {
		var cid, notnull, pk int
		var name, ctype string
		var dflt any
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			t.Fatalf("解析 %s 结构: %v", table, err)
		}
		if name == column {
			return true
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("遍历 %s 结构: %v", table, err)
	}
	return false
}
