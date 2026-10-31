package store

import "time"

// RecentFile 是一条「最近打开」记录（docs/desktop-local-file.md §3.5）。
// 只保存路径与时间，不保存正文 / 编辑状态。
type RecentFile struct {
	Path     string `json:"path"`
	Name     string `json:"name"`
	OpenedAt int64  `json:"openedAt"`
}

const recentFileLimit = 20

// UpsertRecentFile 记录一次打开 / 保存，并裁剪到最近 20 条。
func (s *Store) UpsertRecentFile(path, name string, openedAt int64) error {
	if openedAt == 0 {
		openedAt = time.Now().UnixMilli()
	}
	if _, err := s.db.Exec(`
		INSERT INTO recent_file (path, name, opened_at) VALUES (?, ?, ?)
		ON CONFLICT(path) DO UPDATE SET name = excluded.name, opened_at = excluded.opened_at
	`, path, name, openedAt); err != nil {
		return err
	}
	_, err := s.db.Exec(`
		DELETE FROM recent_file WHERE path NOT IN (
			SELECT path FROM recent_file ORDER BY opened_at DESC LIMIT ?
		)
	`, recentFileLimit)
	return err
}

// ListRecentFiles 按打开时间倒序返回最近记录。
func (s *Store) ListRecentFiles(limit int) ([]RecentFile, error) {
	if limit <= 0 || limit > recentFileLimit {
		limit = recentFileLimit
	}
	rows, err := s.db.Query(`
		SELECT path, name, opened_at FROM recent_file
		 ORDER BY opened_at DESC LIMIT ?
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]RecentFile, 0, limit)
	for rows.Next() {
		var item RecentFile
		if err := rows.Scan(&item.Path, &item.Name, &item.OpenedAt); err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

// DeleteRecentFile 移除一条最近记录。
func (s *Store) DeleteRecentFile(path string) error {
	_, err := s.db.Exec(`DELETE FROM recent_file WHERE path = ?`, path)
	return err
}

// ClearRecentFiles 清空最近记录。
func (s *Store) ClearRecentFiles() error {
	_, err := s.db.Exec(`DELETE FROM recent_file`)
	return err
}
