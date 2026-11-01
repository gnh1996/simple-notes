package store

import (
	"database/sql"
	"errors"
)

// GetUIState 读取桌面端界面状态；不存在时 found=false。
// 只存标签顺序、侧栏宽度这类本机 UI 状态，不参与同步 / 导出。
func (s *Store) GetUIState(key string) (value string, found bool, err error) {
	err = s.db.QueryRow(`SELECT value FROM ui_state WHERE key = ?`, key).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return value, true, nil
}

// SetUIState 写入 / 覆盖界面状态。
func (s *Store) SetUIState(key, value string) error {
	_, err := s.db.Exec(`
		INSERT INTO ui_state (key, value) VALUES (?, ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value
	`, key, value)
	return err
}

// DeleteUIState 删除界面状态（恢复失败 / 清空标签时使用）。
func (s *Store) DeleteUIState(key string) error {
	_, err := s.db.Exec(`DELETE FROM ui_state WHERE key = ?`, key)
	return err
}
