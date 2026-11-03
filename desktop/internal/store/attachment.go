package store

import (
	"database/sql"
	"errors"
	"time"
)

// LocalAttachment 是桌面端本地图片的元数据（docs/desktop-media.md）。
// 二进制放在 desktop.db 同级的 media/local/ 下，不参与同步。
type LocalAttachment struct {
	ID           string `json:"id"`
	ScopeID      string `json:"scopeId"`
	OriginalName string `json:"originalName"`
	MimeType     string `json:"mimeType"`
	SizeBytes    int64  `json:"sizeBytes"`
	StorageKey   string `json:"-"`
	CreateTime   int64  `json:"createTime"`
}

// InsertLocalAttachment 写入一条本地附件元数据。
func (s *Store) InsertLocalAttachment(item LocalAttachment) error {
	if item.CreateTime == 0 {
		item.CreateTime = time.Now().UnixMilli()
	}
	if item.ScopeID == "" {
		item.ScopeID = "local"
	}
	_, err := s.db.Exec(`
		INSERT INTO local_attachment
			(id, scope_id, original_name, mime_type, size_bytes, storage_key, create_time)
		VALUES (?, ?, ?, ?, ?, ?, ?)
	`, item.ID, item.ScopeID, item.OriginalName, item.MimeType, item.SizeBytes, item.StorageKey, item.CreateTime)
	return err
}

// GetLocalAttachment 按 id 读取本地附件元数据。
func (s *Store) GetLocalAttachment(id string) (LocalAttachment, bool, error) {
	var item LocalAttachment
	err := s.db.QueryRow(`
		SELECT id, scope_id, original_name, mime_type, size_bytes, storage_key, create_time
		  FROM local_attachment WHERE id = ?
	`, id).Scan(&item.ID, &item.ScopeID, &item.OriginalName, &item.MimeType,
		&item.SizeBytes, &item.StorageKey, &item.CreateTime)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return LocalAttachment{}, false, nil
		}
		return LocalAttachment{}, false, err
	}
	return item, true, nil
}

// DeleteLocalAttachment 删除本地附件元数据。
func (s *Store) DeleteLocalAttachment(id string) error {
	_, err := s.db.Exec(`DELETE FROM local_attachment WHERE id = ?`, id)
	return err
}

// ListLocalAttachmentIDs 返回全部本地附件 id（清理未引用文件用）。
func (s *Store) ListLocalAttachmentIDs() ([]string, error) {
	rows, err := s.db.Query(`SELECT id FROM local_attachment`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]string, 0, 16)
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}
