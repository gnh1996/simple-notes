package store

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

// 同步域的本地表（docs/desktop-sync.md §4）。
//
// 只落地 M0 需要的最小集合：scope、笔记存根/缓存、文件夹、outbox。
// 正文按需缓存（content_loaded = 0 表示只有索引），未缓存时 content 为 NULL。

// Scope 是一次「服务器 + 账号」的数据归属。
type Scope struct {
	ScopeID   string `json:"scopeId"`
	ServerURL string `json:"serverUrl"`
	UserID    string `json:"userId"`
	Username  string `json:"username"`
	Nickname  string `json:"nickname"`
	Active    bool   `json:"active"`
	CreatedAt int64  `json:"createdAt"`
}

// RemoteNote 是同步索引中的一行（manifest item）。
type RemoteNote struct {
	ID            string
	Kind          string
	Title         string
	FolderID      string
	Pinned        bool
	PublishStatus string
	Version       int64
	UpdatedAt     int64 // epoch ms
	Deleted       bool
}

// NoteRow 是本地缓存的笔记行；Content 为空表示正文尚未拉取。
type NoteRow struct {
	ID                    string   `json:"id"`
	Kind                  string   `json:"kind"`
	Title                 string   `json:"title"`
	FolderID              string   `json:"folderId"`
	Pinned                bool     `json:"pinned"`
	PublishStatus         string   `json:"publishStatus"`
	Tags                  []string `json:"tags"`
	Content               string   `json:"content"`
	ContentLoaded         bool     `json:"contentLoaded"`
	HasUnpublishedChanges bool     `json:"hasUnpublishedChanges"`
	RemoteVersion         int64    `json:"remoteVersion"`
	BaseVersion           int64    `json:"baseVersion"`
	Dirty                 bool     `json:"dirty"`
	Deleted               bool     `json:"deleted"`
	RemoteDeleted         bool     `json:"remoteDeleted"`
	LocalOnly             bool     `json:"localOnly"`
	Conflict              bool     `json:"conflict"`
	LocalHash             string   `json:"-"`
	UpdatedAt             int64    `json:"updatedAt"`
}

// FolderRow 是本地镜像的文件夹。
type FolderRow struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Sort      int    `json:"sort"`
	UpdatedAt int64  `json:"updatedAt"`
}

// OutboxItem 是待重试的写操作。
type OutboxItem struct {
	ScopeID     string
	Entity      string
	ID          string
	Op          string
	Payload     string
	RetryCount  int
	NextRetryAt int64
}

func tagsJSON(tags []string) string {
	if tags == nil {
		return "[]"
	}
	raw, err := json.Marshal(tags)
	if err != nil {
		return "[]"
	}
	return string(raw)
}

func (s *Store) migrateSync() error {
	_, err := s.db.Exec(`
		CREATE TABLE IF NOT EXISTS sync_scope (
			scope_id    TEXT PRIMARY KEY,
			server_url  TEXT NOT NULL,
			user_id     TEXT NOT NULL,
			username    TEXT NOT NULL,
			nickname    TEXT NOT NULL DEFAULT '',
			active      INTEGER NOT NULL DEFAULT 0,
			created_at  INTEGER NOT NULL
		);

		CREATE TABLE IF NOT EXISTS note_local (
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
			remote_deleted          INTEGER NOT NULL DEFAULT 0,
			updated_at              INTEGER NOT NULL DEFAULT 0,
			synced_at               INTEGER,
			PRIMARY KEY (scope_id, id)
		);
		CREATE INDEX IF NOT EXISTS idx_note_local_list
			ON note_local (scope_id, kind, deleted, pinned DESC, updated_at DESC);

		CREATE TABLE IF NOT EXISTS folder_local (
			scope_id   TEXT NOT NULL,
			id         TEXT NOT NULL,
			name       TEXT NOT NULL,
			sort       INTEGER NOT NULL DEFAULT 0,
			dirty      INTEGER NOT NULL DEFAULT 0,
			updated_at INTEGER NOT NULL DEFAULT 0,
			PRIMARY KEY (scope_id, id)
		);

		CREATE TABLE IF NOT EXISTS outbox (
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
	`)
	if err != nil {
		return fmt.Errorf("初始化同步表结构: %w", err)
	}
	// 老库升级：remote_deleted 用于区分「服务端已删除」与「本地待删除」。
	if err := s.ensureColumn("note_local", "remote_deleted", "INTEGER NOT NULL DEFAULT 0"); err != nil {
		return err
	}
	if err := s.ensureColumn("folder_local", "dirty", "INTEGER NOT NULL DEFAULT 0"); err != nil {
		return err
	}
	return nil
}

// ensureColumn 为已有表补列（SQLite 不支持 ADD COLUMN IF NOT EXISTS）。
func (s *Store) ensureColumn(table, column, definition string) error {
	rows, err := s.db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull, pk int
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
	_, err = s.db.Exec(`ALTER TABLE ` + table + ` ADD COLUMN ` + column + ` ` + definition)
	return err
}

// SaveScope 写入并激活一个 scope；同一时间只允许一个远端 scope 处于激活状态。
func (s *Store) SaveScope(scope Scope) error {
	if scope.CreatedAt == 0 {
		scope.CreatedAt = time.Now().UnixMilli()
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.Exec(`UPDATE sync_scope SET active = 0`); err != nil {
		return err
	}
	if _, err := tx.Exec(`
		INSERT INTO sync_scope (scope_id, server_url, user_id, username, nickname, active, created_at)
		VALUES (?, ?, ?, ?, ?, 1, ?)
		ON CONFLICT(scope_id) DO UPDATE SET
			server_url = excluded.server_url,
			username   = excluded.username,
			nickname   = excluded.nickname,
			active     = 1
	`, scope.ScopeID, scope.ServerURL, scope.UserID, scope.Username, scope.Nickname, scope.CreatedAt); err != nil {
		return err
	}
	return tx.Commit()
}

// ActiveScope 返回当前激活的 scope。
func (s *Store) ActiveScope() (Scope, bool, error) {
	var scope Scope
	var active int
	err := s.db.QueryRow(`
		SELECT scope_id, server_url, user_id, username, nickname, active, created_at
		  FROM sync_scope WHERE active = 1 LIMIT 1
	`).Scan(&scope.ScopeID, &scope.ServerURL, &scope.UserID, &scope.Username,
		&scope.Nickname, &active, &scope.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Scope{}, false, nil
	}
	if err != nil {
		return Scope{}, false, err
	}
	scope.Active = active == 1
	return scope, true, nil
}

// DeactivateScopes 让所有 scope 失活（登出 / 切号）；数据保留，切回即恢复。
func (s *Store) DeactivateScopes() error {
	_, err := s.db.Exec(`UPDATE sync_scope SET active = 0`)
	return err
}

// SetBaseURL 记录后端地址（含 /api）。
func (s *Store) SetBaseURL(url string) error {
	return s.Set("server.baseUrl", url)
}

// BaseURL 读取后端地址。
func (s *Store) BaseURL() (string, bool, error) {
	return s.Get("server.baseUrl")
}

// ApplyRemoteNotes 把 manifest 结果写入本地：新行只建存根，已缓存正文保持不变。
func (s *Store) ApplyRemoteNotes(scopeID string, rows []RemoteNote) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	for _, row := range rows {
		v := 0
		if row.Pinned {
			v = 1
		}
		del := 0
		if row.Deleted {
			del = 1
		}
		if _, err := tx.Exec(`
			INSERT INTO note_local
				(scope_id, id, kind, title, folder_id, pinned, publish_status,
				 remote_version, updated_at, deleted, remote_deleted)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(scope_id, id) DO UPDATE SET
				kind           = excluded.kind,
				title          = excluded.title,
				folder_id      = excluded.folder_id,
				pinned         = excluded.pinned,
				publish_status = excluded.publish_status,
				remote_version = excluded.remote_version,
				updated_at     = excluded.updated_at,
				-- 本地有未推送修改时不跟随远端删除，交给冲突处理
				deleted        = CASE WHEN note_local.dirty = 1 THEN note_local.deleted
				                      ELSE excluded.deleted END,
				remote_deleted = excluded.remote_deleted
		`, scopeID, row.ID, row.Kind, row.Title, nullable(row.FolderID), v,
			nullable(row.PublishStatus), row.Version, row.UpdatedAt, del, del); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ListNotes 返回本地索引（不含未缓存的正文）。
func (s *Store) ListNotes(scopeID, kind, folderID, keyword string) ([]NoteRow, error) {
	sqlText := `
		SELECT id, kind, title, folder_id, pinned, publish_status, tags_json,
		       content, content_loaded, has_unpublished_changes, remote_version, base_version,
		       dirty, deleted, remote_deleted, updated_at
		  FROM note_local
		 WHERE scope_id = ? AND kind = ? AND deleted = 0`
	args := []any{scopeID, kind}
	if folderID != "" {
		sqlText += ` AND folder_id = ?`
		args = append(args, folderID)
	}
	if keyword != "" {
		sqlText += ` AND title LIKE '%' || ? || '%'`
		args = append(args, keyword)
	}
	sqlText += ` ORDER BY pinned DESC, updated_at DESC`

	rows, err := s.db.Query(sqlText, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]NoteRow, 0, 32)
	for rows.Next() {
		var row NoteRow
		var folder, content, publish sql.NullString
		var hasChanges sql.NullInt64
		var tags string
		var loaded, pinned, dirty, deleted, remoteDeleted int
		if err := rows.Scan(&row.ID, &row.Kind, &row.Title, &folder, &pinned, &publish,
			&tags, &content, &loaded, &hasChanges, &row.RemoteVersion, &row.BaseVersion,
			&dirty, &deleted, &remoteDeleted, &row.UpdatedAt); err != nil {
			return nil, err
		}
		row.FolderID = folder.String
		row.Content = content.String
		row.PublishStatus = publish.String
		row.HasUnpublishedChanges = hasChanges.Valid && hasChanges.Int64 == 1
		row.Tags = parseTags(tags)
		row.ContentLoaded = loaded == 1
		row.Pinned = pinned == 1
		row.Dirty = dirty == 1
		row.Deleted = deleted == 1
		row.RemoteDeleted = remoteDeleted == 1
		out = append(out, row)
	}
	return out, rows.Err()
}

// GetNote 按 id 读取本地行（scope 内唯一）。
func (s *Store) GetNote(scopeID, id string) (NoteRow, bool, error) {
	var row NoteRow
	var folder, content, publish sql.NullString
	var hasChanges sql.NullInt64
	var tags string
	var loaded, pinned, dirty, deleted, remoteDeleted int
	err := s.db.QueryRow(`
		SELECT id, kind, title, folder_id, pinned, publish_status, tags_json,
		       content, content_loaded, has_unpublished_changes, remote_version, base_version,
		       dirty, deleted, remote_deleted, updated_at
		  FROM note_local WHERE scope_id = ? AND id = ?
	`, scopeID, id).Scan(&row.ID, &row.Kind, &row.Title, &folder, &pinned, &publish,
		&tags, &content, &loaded, &hasChanges, &row.RemoteVersion, &row.BaseVersion,
		&dirty, &deleted, &remoteDeleted, &row.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return NoteRow{}, false, nil
	}
	if err != nil {
		return NoteRow{}, false, err
	}
	row.FolderID = folder.String
	row.Content = content.String
	row.PublishStatus = publish.String
	row.HasUnpublishedChanges = hasChanges.Valid && hasChanges.Int64 == 1
	row.Tags = parseTags(tags)
	row.ContentLoaded = loaded == 1
	row.Pinned = pinned == 1
	row.Dirty = dirty == 1
	row.Deleted = deleted == 1
	row.RemoteDeleted = remoteDeleted == 1
	return row, true, nil
}

// SavePostState 更新文章发布状态 / 是否有未发布更改（打开详情时写入）。
func (s *Store) SavePostState(scopeID, id, status string, hasChanges bool) error {
	_, err := s.db.Exec(`
		UPDATE note_local SET publish_status = ?, has_unpublished_changes = ?
		 WHERE scope_id = ? AND id = ?
	`, nullable(status), boolInt(hasChanges), scopeID, id)
	return err
}

// SaveNoteDetail 缓存正文并记录同步基线（M1 起由推送流程复用）。
func (s *Store) SaveNoteDetail(scopeID string, row NoteRow, tagsJSON, hash string) error {
	_, err := s.db.Exec(`
		UPDATE note_local SET
			title          = ?,
			content        = ?,
			content_loaded = 1,
			folder_id      = ?,
			pinned         = ?,
			publish_status = ?,
			tags_json      = ?,
			has_unpublished_changes = ?,
			remote_version = ?,
			base_version   = ?,
			local_hash     = ?,
			base_hash      = ?,
			deleted        = 0,
			remote_deleted = 0,
			synced_at      = ?
		WHERE scope_id = ? AND id = ?
	`, row.Title, row.Content, nullable(row.FolderID), boolInt(row.Pinned),
		nullable(row.PublishStatus), tagsJSON, boolInt(row.HasUnpublishedChanges),
		row.RemoteVersion, row.RemoteVersion, hash, hash, time.Now().UnixMilli(), scopeID, row.ID)
	return err
}

// InsertNoteLocal 离线新建：正文、hash、dirty 一次写入，版本从 0 开始。
func (s *Store) InsertNoteLocal(scopeID string, row NoteRow, tagsJSON, hash string) error {
	_, err := s.db.Exec(`
		INSERT INTO note_local
			(scope_id, id, kind, title, content, content_loaded, folder_id, pinned, tags_json,
			 remote_version, base_version, local_hash, dirty, deleted, remote_deleted, updated_at)
		VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, 0, 0, ?, 1, 0, 0, ?)
	`, scopeID, row.ID, row.Kind, row.Title, row.Content, nullable(row.FolderID),
		boolInt(row.Pinned), tagsJSON, hash, time.Now().UnixMilli())
	return err
}

// GetNoteAny 跨 scope 查找（纯本地副本 / 冲突副本的 id 只存在于 local scope）。
func (s *Store) GetNoteAny(id string) (string, NoteRow, bool, error) {
	row, found, err := s.GetNote("local", id)
	if err != nil {
		return "", NoteRow{}, false, err
	}
	if found {
		return "local", row, true, nil
	}
	scope, ok, err := s.ActiveScope()
	if err != nil || !ok {
		return "", NoteRow{}, false, err
	}
	row, found, err = s.GetNote(scope.ScopeID, id)
	if err != nil || !found {
		return scope.ScopeID, NoteRow{}, false, err
	}
	return scope.ScopeID, row, true, nil
}

// SaveNoteLocal 写入本地编辑（M1）：标记 dirty，但不改同步基线。
func (s *Store) SaveNoteLocal(scopeID string, row NoteRow, tagsJSON, hash string) error {
	_, err := s.db.Exec(`
		UPDATE note_local SET
			title          = ?,
			content        = ?,
			content_loaded = 1,
			folder_id      = ?,
			pinned         = ?,
			tags_json      = ?,
			local_hash     = ?,
			dirty          = 1,
			updated_at     = ?
		WHERE scope_id = ? AND id = ?
	`, row.Title, row.Content, nullable(row.FolderID), boolInt(row.Pinned), tagsJSON,
		hash, time.Now().UnixMilli(), scopeID, row.ID)
	return err
}

// MarkNoteSynced 把一次成功推送的版本写回本地，清除 dirty。
func (s *Store) MarkNoteSynced(scopeID, id string, version int64, hash string) error {
	_, err := s.db.Exec(`
		UPDATE note_local SET
			remote_version = ?,
			base_version   = ?,
			base_hash      = ?,
			local_hash     = ?,
			dirty          = 0,
			deleted        = 0,
			remote_deleted = 0,
			synced_at      = ?
		WHERE scope_id = ? AND id = ?
	`, version, version, hash, hash, time.Now().UnixMilli(), scopeID, id)
	return err
}

// UpsertRemoteFolders 用服务端列表同步本地镜像：保留本地 dirty 文件夹（未推送的改名）。
func (s *Store) UpsertRemoteFolders(scopeID string, folders []FolderRow) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if len(folders) == 0 {
		if _, err := tx.Exec(`DELETE FROM folder_local WHERE scope_id = ? AND dirty = 0`, scopeID); err != nil {
			return err
		}
	} else {
		args := []any{scopeID}
		placeholders := make([]string, 0, len(folders))
		for _, folder := range folders {
			placeholder := "?"
			placeholders = append(placeholders, placeholder)
			args = append(args, folder.ID)
		}
		if _, err := tx.Exec(`
			DELETE FROM folder_local
			 WHERE scope_id = ? AND dirty = 0
			   AND id NOT IN (`+strings.Join(placeholders, ",")+`)`, args...); err != nil {
			return err
		}
	}

	for _, folder := range folders {
		if _, err := tx.Exec(`
			INSERT INTO folder_local (scope_id, id, name, sort, dirty, updated_at)
			VALUES (?, ?, ?, ?, 0, ?)
			ON CONFLICT(scope_id, id) DO UPDATE SET
				name       = excluded.name,
				sort       = excluded.sort,
				updated_at = excluded.updated_at
			WHERE folder_local.dirty = 0
		`, scopeID, folder.ID, folder.Name, folder.Sort, folder.UpdatedAt); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// SaveFolderLocal 写入本地新建 / 改名（dirty=1）。
func (s *Store) SaveFolderLocal(scopeID, id, name string, sort int) error {
	_, err := s.db.Exec(`
		INSERT INTO folder_local (scope_id, id, name, sort, dirty, updated_at)
		VALUES (?, ?, ?, ?, 1, ?)
		ON CONFLICT(scope_id, id) DO UPDATE SET
			name = excluded.name, sort = excluded.sort, dirty = 1, updated_at = excluded.updated_at
	`, scopeID, id, name, sort, time.Now().UnixMilli())
	return err
}

// DeleteFolderLocal 删除本地镜像（推送失败时由 outbox 重试服务端删除）。
func (s *Store) DeleteFolderLocal(scopeID, id string) error {
	_, err := s.db.Exec(`DELETE FROM folder_local WHERE scope_id = ? AND id = ?`, scopeID, id)
	return err
}

// MarkFolderSynced 清除文件夹 dirty（重放成功后）。
func (s *Store) MarkFolderSynced(scopeID, id string) error {
	_, err := s.db.Exec(`UPDATE folder_local SET dirty = 0 WHERE scope_id = ? AND id = ?`, scopeID, id)
	return err
}

// MarkNoteDeletedLocal 本地删除：先墓碑 + dirty，等待推送。
func (s *Store) MarkNoteDeletedLocal(scopeID, id string) error {
	_, err := s.db.Exec(`
		UPDATE note_local SET deleted = 1, dirty = 1, updated_at = ?
		 WHERE scope_id = ? AND id = ?
	`, time.Now().UnixMilli(), scopeID, id)
	return err
}

// MarkLocalOnlySaved 纯本地笔记保存后清除 dirty（不参与推送）。
func (s *Store) MarkLocalOnlySaved(scopeID, id string) error {
	_, err := s.db.Exec(`UPDATE note_local SET dirty = 0 WHERE scope_id = ? AND id = ?`, scopeID, id)
	return err
}

// MarkNoteCreateSynced 离线新建推送成功：只更新版本，不动正文 hash。
func (s *Store) MarkNoteCreateSynced(scopeID, id string, version int64) error {
	_, err := s.db.Exec(`
		UPDATE note_local SET remote_version = ?, base_version = ?, dirty = 0, synced_at = ?
		 WHERE scope_id = ? AND id = ?
	`, version, version, time.Now().UnixMilli(), scopeID, id)
	return err
}

// MarkNoteDeleteSynced 服务端删除成功：保留墓碑，清除 dirty。
func (s *Store) MarkNoteDeleteSynced(scopeID, id string) error {
	_, err := s.db.Exec(`
		UPDATE note_local SET deleted = 1, dirty = 0, remote_deleted = 1, synced_at = ?
		 WHERE scope_id = ? AND id = ?
	`, time.Now().UnixMilli(), scopeID, id)
	return err
}

// DeleteNoteLocal 直接移除本地行（远端删除且本地无改动 / 或用户放弃本地副本）。
func (s *Store) DeleteNoteLocal(scopeID, id string) error {
	_, err := s.db.Exec(`DELETE FROM note_local WHERE scope_id = ? AND id = ?`, scopeID, id)
	return err
}

// CopyNoteLocal 把一行复制到指定 scope（删除冲突时「保留为本地副本」用）。
func (s *Store) CopyNoteLocal(fromScope string, row NoteRow, toScope string) (string, error) {
	newID := NewUUID()
	_, err := s.db.Exec(`
		INSERT INTO note_local
			(scope_id, id, kind, title, content, content_loaded, folder_id, pinned, tags_json,
			 publish_status, remote_version, base_version, local_hash, base_hash, dirty, deleted,
			 remote_deleted, updated_at, synced_at)
		VALUES (?, ?, ?, ?, ?, 1, NULL, ?, ?, NULL, 0, 0, ?, NULL, 0, 0, 0, ?, NULL)
	`, toScope, newID, row.Kind, row.Title+"（冲突副本）", row.Content, boolInt(row.Pinned),
		tagsJSON(row.Tags), row.LocalHash, time.Now().UnixMilli())
	if err != nil {
		return "", err
	}
	return newID, nil
}

// Enqueue 写入 outbox（同一实体合并为最新一条）。
func (s *Store) Enqueue(scopeID, entity, id, op, payload string) error {
	_, err := s.db.Exec(`
		INSERT INTO outbox (scope_id, entity, id, op, payload, retry_count, next_retry_at, created_at)
		VALUES (?, ?, ?, ?, ?, 0, 0, ?)
		ON CONFLICT(scope_id, entity, id) DO UPDATE SET
			op = excluded.op, payload = excluded.payload, retry_count = 0, next_retry_at = 0
	`, scopeID, entity, id, op, payload, time.Now().UnixMilli())
	return err
}

// PendingOutbox 返回待重试项。
func (s *Store) PendingOutbox(scopeID string) ([]OutboxItem, error) {
	rows, err := s.db.Query(`
		SELECT scope_id, entity, id, op, payload, retry_count, next_retry_at
		  FROM outbox WHERE scope_id = ? ORDER BY created_at ASC
	`, scopeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]OutboxItem, 0, 8)
	for rows.Next() {
		var item OutboxItem
		if err := rows.Scan(&item.ScopeID, &item.Entity, &item.ID, &item.Op,
			&item.Payload, &item.RetryCount, &item.NextRetryAt); err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

// RemoveOutbox 删除已被成功重放的项；op 条件避免把同 id 新入队的后续操作一起删掉。
func (s *Store) RemoveOutbox(scopeID, entity, id, op string) error {
	_, err := s.db.Exec(`DELETE FROM outbox WHERE scope_id = ? AND entity = ? AND id = ? AND op = ?`,
		scopeID, entity, id, op)
	return err
}

// BumpOutbox 记录失败次数并安排下次重试（指数退避，上限 5 分钟）。
func (s *Store) BumpOutbox(scopeID, entity, id string) error {
	var retry int
	if err := s.db.QueryRow(`SELECT retry_count FROM outbox WHERE scope_id = ? AND entity = ? AND id = ?`,
		scopeID, entity, id).Scan(&retry); err != nil {
		return err
	}
	delay := time.Duration(1<<min(retry, 8)) * 2 * time.Second
	if delay > 5*time.Minute {
		delay = 5 * time.Minute
	}
	_, err := s.db.Exec(`
		UPDATE outbox SET retry_count = retry_count + 1, next_retry_at = ?
		 WHERE scope_id = ? AND entity = ? AND id = ?
	`, time.Now().Add(delay).UnixMilli(), scopeID, entity, id)
	return err
}

// NewUUID 生成 v4 UUID（离线新建 / 冲突副本使用）。
func NewUUID() string {
	var buf [16]byte
	if _, err := rand.Read(buf[:]); err != nil {
		panic("生成 UUID 失败: " + err.Error())
	}
	buf[6] = (buf[6] & 0x0f) | 0x40
	buf[8] = (buf[8] & 0x3f) | 0x80
	return hex.EncodeToString(buf[0:4]) + "-" + hex.EncodeToString(buf[4:6]) + "-" +
		hex.EncodeToString(buf[6:8]) + "-" + hex.EncodeToString(buf[8:10]) + "-" +
		hex.EncodeToString(buf[10:16])
}

// ListFolders 返回本地文件夹镜像。
func (s *Store) ListFolders(scopeID string) ([]FolderRow, error) {
	rows, err := s.db.Query(`
		SELECT id, name, sort, updated_at FROM folder_local
		 WHERE scope_id = ? ORDER BY sort ASC, name ASC
	`, scopeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]FolderRow, 0, 16)
	for rows.Next() {
		var folder FolderRow
		if err := rows.Scan(&folder.ID, &folder.Name, &folder.Sort, &folder.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, folder)
	}
	return out, rows.Err()
}

func nullable(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func boolInt(value bool) int {
	if value {
		return 1
	}
	return 0
}

func parseTags(raw string) []string {
	if raw == "" || raw == "[]" {
		return []string{}
	}
	// tags_json 由本端写入，格式固定为 JSON 字符串数组；解析失败按空处理，避免索引页崩溃。
	var tags []string
	if err := json.Unmarshal([]byte(raw), &tags); err != nil {
		return []string{}
	}
	return tags
}
