// Package store 提供桌面端的本地 SQLite 存储。
//
// 表结构由 internal/store/migrations 的有序迁移维护（docs/desktop-local-schema.md）：
// 启动时读取 PRAGMA user_version，按需执行未应用的迁移；版本高于应用支持范围时拒绝启动。
package store

import (
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite" // 纯 Go 驱动，避免额外的 CGO / gcc 依赖
)

// Store 是本地数据库句柄。
type Store struct {
	db   *sql.DB
	path string
}

// Open 在用户配置目录下打开（或创建）桌面端数据库。
func Open() (*Store, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return nil, fmt.Errorf("定位用户配置目录: %w", err)
	}
	return OpenAt(filepath.Join(dir, "simple-notes", "desktop.db"))
}

// OpenAt 在指定路径打开数据库；测试与后续多实例场景可直接复用。
func OpenAt(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("创建数据库目录: %w", err)
	}

	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("打开 SQLite: %w", err)
	}
	// 单连接足够桌面端当前规模；WAL 提升读写并发并降低 fsync 抖动。
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;`); err != nil {
		db.Close()
		return nil, fmt.Errorf("初始化 SQLite: %w", err)
	}

	store := &Store{db: db, path: path}
	if err := store.migrate(); err != nil {
		db.Close()
		return nil, err
	}

	return store, nil
}

// Path 返回数据库文件路径（用于诊断展示）。
func (s *Store) Path() string { return s.path }

// Get 读取键值；不存在时返回 found=false。
func (s *Store) Get(key string) (value string, found bool, err error) {
	err = s.db.QueryRow(`SELECT value FROM app_kv WHERE key = ?`, key).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return value, true, nil
}

// Set 写入键值（upsert）。
func (s *Store) Set(key, value string) error {
	_, err := s.db.Exec(`
		INSERT INTO app_kv (key, value, update_time) VALUES (?, ?, datetime('now'))
		ON CONFLICT(key) DO UPDATE SET value = excluded.value, update_time = datetime('now')
	`, key, value)
	return err
}

// Delete 删除键值。
func (s *Store) Delete(key string) error {
	_, err := s.db.Exec(`DELETE FROM app_kv WHERE key = ?`, key)
	return err
}

// Token 读取本地会话令牌；不存在时返回 found=false。
func (s *Store) Token() (token string, found bool, err error) {
	err = s.db.QueryRow(`SELECT token FROM auth_session WHERE id = 1`).Scan(&token)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return token, true, nil
}

// SetToken 写入 / 覆盖本地会话令牌。
func (s *Store) SetToken(token string) error {
	_, err := s.db.Exec(`
		INSERT INTO auth_session (id, token, update_time) VALUES (1, ?, datetime('now'))
		ON CONFLICT(id) DO UPDATE SET token = excluded.token, update_time = datetime('now')
	`, token)
	return err
}

// ClearToken 删除本地会话令牌。
func (s *Store) ClearToken() error {
	_, err := s.db.Exec(`DELETE FROM auth_session WHERE id = 1`)
	return err
}

// Close 关闭数据库。
func (s *Store) Close() error { return s.db.Close() }
