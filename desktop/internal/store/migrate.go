package store

import (
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// ErrSchemaTooNew 表示本地数据库由更新版本的桌面端创建，当前应用无法安全读写。
// UI 应把它翻译成「请升级应用」而不是「数据库损坏」。
var ErrSchemaTooNew = errors.New("本地数据库版本高于当前应用")

// migration 是一条结构变更。file 与 fn 二选一：
//   - file：常规 DDL，写在 migrations/*.sql；
//   - fn：无法用纯 SQL 表达的条件性 DDL / 数据回填（必须幂等）。
type migration struct {
	version int
	name    string
	file    string
	fn      func(*sql.Tx) error
	// rebuild 表示需要重建表：执行器负责在事务外关闭外键，提交后做 foreign_key_check。
	rebuild bool
}

// migrations 是唯一的迁移注册表，只允许在末尾追加（docs/desktop-local-schema.md §4.7）。
var migrations = []migration{
	{version: 1, name: "init", file: "0001_init.sql"},
	{version: 2, name: "align_legacy_columns", fn: alignLegacyColumns},
	{version: 3, name: "local_media", file: "0003_local_media.sql"},
	{version: 4, name: "local_folder_names", file: "0004_local_folder_names.sql"},
	{version: 5, name: "folder_tree", file: "0005_folder_tree.sql"},
}

//go:embed migrations/*.sql
var migrationFS embed.FS

// migrate 把本地库推进到注册表的最新版本。
//
// 约定（docs/desktop-local-schema.md §4.2）：
//   - 每条迁移一个事务，DDL 与 user_version 同事务提交；
//   - 库版本高于应用支持范围时 fail-fast，不读不写；
//   - 迁移前备份，备份失败则中止（不允许无备份做结构变更）。
func (s *Store) migrate() error {
	if err := validateMigrations(migrations); err != nil {
		return err
	}

	current, err := s.userVersion()
	if err != nil {
		return err
	}
	latest := migrations[len(migrations)-1].version
	if current > latest {
		return fmt.Errorf("%w（数据库 v%d，应用支持 v%d）", ErrSchemaTooNew, current, latest)
	}
	if current == latest {
		return nil
	}

	if err := s.backupBeforeMigrate(current); err != nil {
		return fmt.Errorf("迁移前备份失败: %w", err)
	}

	for _, m := range migrations {
		if m.version <= current {
			continue
		}
		if err := s.applyMigration(m); err != nil {
			return fmt.Errorf("迁移 %04d_%s 失败: %w", m.version, m.name, err)
		}
	}
	return nil
}

// validateMigrations 在启动时校验注册表，把「编号写错 / 文件漏提交」变成显式错误。
func validateMigrations(list []migration) error {
	if len(list) == 0 {
		return errors.New("迁移注册表为空")
	}
	for i, m := range list {
		want := i + 1
		if m.version != want {
			return fmt.Errorf("迁移编号不连续：第 %d 条为 %d，期望 %d", i+1, m.version, want)
		}
		if strings.TrimSpace(m.name) == "" {
			return fmt.Errorf("迁移 %04d 缺少名称", m.version)
		}
		hasFile, hasFn := m.file != "", m.fn != nil
		if hasFile == hasFn {
			return fmt.Errorf("迁移 %04d_%s 必须且只能指定 file 或 fn", m.version, m.name)
		}
		if !hasFile {
			continue
		}
		if prefix := fmt.Sprintf("%04d_", m.version); !strings.HasPrefix(m.file, prefix) {
			return fmt.Errorf("迁移文件 %q 的编号与版本 %d 不一致", m.file, m.version)
		}
		if _, err := migrationFS.ReadFile("migrations/" + m.file); err != nil {
			return fmt.Errorf("迁移文件 %q 不可读: %w", m.file, err)
		}
	}
	return nil
}

func (s *Store) userVersion() (int, error) {
	var version int
	if err := s.db.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil {
		return 0, fmt.Errorf("读取本地数据库版本: %w", err)
	}
	return version, nil
}

func (s *Store) applyMigration(m migration) error {
	if m.rebuild {
		// 外键开关在事务内是 no-op，必须在 BEGIN 之前切换，结束后恢复原值。
		previous, err := s.foreignKeysEnabled()
		if err != nil {
			return err
		}
		if err := s.setForeignKeys(false); err != nil {
			return err
		}
		defer func() { _ = s.setForeignKeys(previous) }()
	}

	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	if m.fn != nil {
		if err := m.fn(tx); err != nil {
			return err
		}
	} else {
		raw, err := migrationFS.ReadFile("migrations/" + m.file)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(string(raw)); err != nil {
			return err
		}
	}

	// PRAGMA 不支持绑定参数；m.version 是代码内常量，不存在注入面。
	if _, err := tx.Exec(fmt.Sprintf("PRAGMA user_version = %d", m.version)); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}

	if m.rebuild {
		return s.checkForeignKeys()
	}
	return nil
}

// backupBeforeMigrate 在真正发生结构变更前生成一份可直接独立打开的完整副本。
// 全新安装（库里还没有任何用户表）不备份空库。
func (s *Store) backupBeforeMigrate(from int) error {
	hasTables, err := s.hasUserTables()
	if err != nil {
		return err
	}
	if !hasTables {
		return nil
	}

	dst := fmt.Sprintf("%s.bak.v%d", s.path, from)
	// VACUUM INTO 要求目标文件不存在；上次失败的残留先清掉。
	_ = os.Remove(dst)
	// 路径来自 os.UserConfigDir()，只需转义单引号即可安全内联。
	quoted := "'" + strings.ReplaceAll(dst, "'", "''") + "'"
	if _, err := s.db.Exec(`VACUUM INTO ` + quoted); err != nil {
		return err
	}
	s.cleanupOldBackups(dst)
	return nil
}

func (s *Store) hasUserTables() (bool, error) {
	var count int
	err := s.db.QueryRow(`
		SELECT count(*) FROM sqlite_master
		 WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
	`).Scan(&count)
	return count > 0, err
}

// cleanupOldBackups 只保留最近一份备份（刚生成的那份）。
// 用 ReadDir + 前缀过滤而不是 Glob，避免路径里的 [ ? * 被当作通配符。
func (s *Store) cleanupOldBackups(keep string) {
	dir := filepath.Dir(s.path)
	prefix := filepath.Base(s.path) + ".bak.v"
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasPrefix(entry.Name(), prefix) {
			continue
		}
		path := filepath.Join(dir, entry.Name())
		if path != keep {
			_ = os.Remove(path)
		}
	}
}

func (s *Store) foreignKeysEnabled() (bool, error) {
	var enabled int
	if err := s.db.QueryRow(`PRAGMA foreign_keys`).Scan(&enabled); err != nil {
		return false, err
	}
	return enabled == 1, nil
}

func (s *Store) setForeignKeys(enabled bool) error {
	value := "OFF"
	if enabled {
		value = "ON"
	}
	_, err := s.db.Exec(`PRAGMA foreign_keys=` + value)
	return err
}

// checkForeignKeys 在重建表提交后校验引用完整性；失败说明迁移逻辑有缺陷。
func (s *Store) checkForeignKeys() error {
	rows, err := s.db.Query(`PRAGMA foreign_key_check`)
	if err != nil {
		return err
	}
	defer rows.Close()
	if rows.Next() {
		return errors.New("外键校验失败：迁移后存在孤立的引用行")
	}
	return rows.Err()
}

// alignLegacyColumns 把「无版本号时代」由 ensureColumn 补的列收编为正式迁移。
// 新库在 0001 里已包含这些列，这里全部命中「已存在」分支，是空操作。
func alignLegacyColumns(tx *sql.Tx) error {
	columns := []struct {
		table      string
		column     string
		definition string
	}{
		{"note_local", "remote_deleted", "INTEGER NOT NULL DEFAULT 0"},
		{"folder_local", "dirty", "INTEGER NOT NULL DEFAULT 0"},
		{"folder_local", "create_time", "INTEGER NOT NULL DEFAULT 0"},
	}
	for _, c := range columns {
		if err := ensureColumnTx(tx, c.table, c.column, c.definition); err != nil {
			return fmt.Errorf("%s.%s: %w", c.table, c.column, err)
		}
	}
	return nil
}

// ensureColumnTx 为已有表补列（SQLite 不支持 ADD COLUMN IF NOT EXISTS）。
// 表名与列名来自代码常量，不拼接用户输入。
func ensureColumnTx(tx *sql.Tx, table, column, definition string) error {
	rows, err := tx.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return err
	}
	defer rows.Close()

	for rows.Next() {
		var cid, notnull, pk int
		var name, ctype string
		var dflt any
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			return err
		}
		if name == column {
			return nil
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	// 先关闭游标，再在同一事务里执行 DDL。
	if err := rows.Close(); err != nil {
		return err
	}
	_, err = tx.Exec(`ALTER TABLE ` + table + ` ADD COLUMN ` + column + ` ` + definition)
	return err
}

// StartupMessage 把启动期的数据库错误翻译成面向用户的说明（main.go 弹窗用）。
func StartupMessage(err error) string {
	if errors.Is(err, ErrSchemaTooNew) {
		return fmt.Sprintf("%v。\n\n请升级 Simple Notes；或使用数据库同目录下的 desktop.db.bak.v* 恢复后再试。", err)
	}
	return fmt.Sprintf("本地数据库初始化失败：%v\n\n数据库文件未被删除，可查看 desktop-error.log 或联系支持。", err)
}
