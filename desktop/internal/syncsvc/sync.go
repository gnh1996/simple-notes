// Package syncsvc 拉取服务端索引（manifest）并写入本地。
//
// M0 只做索引同步：不拉正文（打开时由 notes.Open 按需拉取），也不推送本地修改。
package syncsvc

import (
	"encoding/json"
	"errors"
	"net/url"
	"strings"
	"time"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/store"
)

// Service 通过 Wails binding 暴露同步能力。
type Service struct {
	db  *store.Store
	api *api.Service
}

// Result 是一次索引同步的统计。
type Result struct {
	Notes    int   `json:"notes"`
	Posts    int   `json:"posts"`
	Folders  int   `json:"folders"`
	SyncedAt int64 `json:"syncedAt"`
	Pending  int   `json:"pending"`
}

// FlushResult 是 outbox 重放结果。
type FlushResult struct {
	Replayed int `json:"replayed"`
	Pending  int `json:"pending"`
}

// New 创建同步服务。
func New(db *store.Store, apiService *api.Service) *Service {
	return &Service{db: db, api: apiService}
}

type syncItem struct {
	ID            string `json:"id"`
	Kind          string `json:"kind"`
	Title         string `json:"title"`
	FolderID      string `json:"folderId"`
	Pinned        bool   `json:"pinned"`
	PublishStatus string `json:"publishStatus"`
	Version       int64  `json:"version"`
	UpdateTime    string `json:"updateTime"`
	Deleted       bool   `json:"deleted"`
}

type syncPage struct {
	Items      []syncItem `json:"items"`
	NextCursor string     `json:"nextCursor"`
	HasMore    bool       `json:"hasMore"`
}

type folderItem struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Sort       int    `json:"sort"`
	UpdateTime string `json:"updateTime"`
}

// Refresh 先重放 outbox，再拉取随手记 / 文章 / 文件夹索引，返回统计。
func (s *Service) Refresh() (*Result, error) {
	scope, ok, err := s.db.ActiveScope()
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errors.New("请先登录")
	}

	// 先推本地待办，避免把「本地删除」又当作远端变更拉回来。
	_, _ = s.Flush()

	notes, err := s.pull(scope.ScopeID, "notes")
	if err != nil {
		return nil, err
	}
	posts, err := s.pull(scope.ScopeID, "posts")
	if err != nil {
		return nil, err
	}

	var folders []folderItem
	if err := s.api.Get("/desktop/note-folders", &folders); err != nil {
		return nil, err
	}
	rows := make([]store.FolderRow, 0, len(folders))
	for _, folder := range folders {
		rows = append(rows, store.FolderRow{
			ID:        folder.ID,
			Name:      folder.Name,
			Sort:      folder.Sort,
			UpdatedAt: parseTime(folder.UpdateTime),
		})
	}
	if err := s.db.UpsertRemoteFolders(scope.ScopeID, rows); err != nil {
		return nil, err
	}

	pending, _ := s.db.PendingOutbox(scope.ScopeID)
	return &Result{
		Notes:    notes,
		Posts:    posts,
		Folders:  len(rows),
		SyncedAt: time.Now().UnixMilli(),
		Pending:  len(pending),
	}, nil
}

// Pending 返回 outbox 待重试数量（不做重放）。
func (s *Service) Pending() (int, error) {
	scope, ok, err := s.db.ActiveScope()
	if err != nil || !ok {
		return 0, err
	}
	items, err := s.db.PendingOutbox(scope.ScopeID)
	if err != nil {
		return 0, err
	}
	return len(items), nil
}

// Flush 重放 outbox：按实体分发，成功即移除，失败按指数退避重试。
func (s *Service) Flush() (*FlushResult, error) {
	scope, ok, err := s.db.ActiveScope()
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errors.New("请先登录")
	}
	items, err := s.db.PendingOutbox(scope.ScopeID)
	if err != nil {
		return nil, err
	}
	now := time.Now().UnixMilli()
	replayed := 0
	for _, item := range items {
		if item.NextRetryAt > now {
			continue
		}
		if s.replay(scope.ScopeID, item) {
			_ = s.db.RemoveOutbox(scope.ScopeID, item.Entity, item.ID, item.Op)
			replayed++
		} else {
			_ = s.db.BumpOutbox(scope.ScopeID, item.Entity, item.ID)
		}
	}
	pending, _ := s.db.PendingOutbox(scope.ScopeID)
	return &FlushResult{Replayed: replayed, Pending: len(pending)}, nil
}

func (s *Service) replayCreate(scopeID string, item store.OutboxItem) bool {
	var payload struct {
		Kind string         `json:"kind"`
		Body map[string]any `json:"body"`
	}
	if err := json.Unmarshal([]byte(item.Payload), &payload); err != nil || payload.Body == nil {
		return false
	}
	path := "/desktop/notes"
	if payload.Kind == "POST" {
		path = "/desktop/posts"
	}
	body, _ := json.Marshal(payload.Body)
	resp, err := s.api.Do(api.Request{Method: "POST", Path: path, Body: string(body)})
	if err != nil {
		return false
	}
	if resp.Status == 200 || resp.Status == 201 {
		var detail struct {
			Version int64 `json:"version"`
		}
		_ = json.Unmarshal([]byte(resp.Body), &detail)
		_ = s.db.MarkNoteCreateSynced(scopeID, item.ID, detail.Version)
		s.enqueueUpdateIfDiverged(scopeID, item.ID, payload.Kind, detail.Version, payload.Body)
		return true
	}
	if resp.Status == 409 {
		// 上一次实际已成功但没收到响应：按已存在处理。
		detailPath := "/desktop/notes/" + item.ID
		if payload.Kind == "POST" {
			detailPath = "/desktop/posts/" + item.ID
		}
		var detail struct {
			Version int64 `json:"version"`
		}
		if err := s.api.Get(detailPath, &detail); err == nil {
			_ = s.db.MarkNoteCreateSynced(scopeID, item.ID, detail.Version)
			return true
		}
	}
	return false
}

func (s *Service) enqueueUpdateIfDiverged(scopeID, id, kind string, version int64, sent map[string]any) {
	row, found, err := s.db.GetNote(scopeID, id)
	if err != nil || !found {
		return
	}
	sentTitle, _ := sent["title"].(string)
	sentContent, _ := sent["content"].(string)
	if strings.TrimSpace(row.Title) == strings.TrimSpace(sentTitle) && row.Content == sentContent {
		return
	}
	body := map[string]any{
		"version": version,
		"title":   row.Title,
		"content": row.Content,
		"tags":    row.Tags,
		"pinned":  row.Pinned,
	}
	if row.FolderID != "" {
		body["folderId"] = row.FolderID
	}
	payload, _ := json.Marshal(map[string]any{"kind": kind, "body": body})
	_ = s.db.Enqueue(scopeID, "note", id, "update", string(payload))
}

func (s *Service) replayUpdate(scopeID string, item store.OutboxItem) bool {
	var payload struct {
		Kind string         `json:"kind"`
		Body map[string]any `json:"body"`
	}
	if err := json.Unmarshal([]byte(item.Payload), &payload); err != nil || payload.Body == nil {
		return false
	}
	path := "/desktop/notes/" + item.ID
	if payload.Kind == "POST" {
		path = "/desktop/posts/" + item.ID
	}
	body, _ := json.Marshal(payload.Body)
	resp, err := s.api.Do(api.Request{Method: "PUT", Path: path, Body: string(body)})
	if err != nil {
		return false
	}
	if resp.Status == 200 {
		var detail struct {
			Version int64 `json:"version"`
		}
		_ = json.Unmarshal([]byte(resp.Body), &detail)
		_ = s.db.MarkNoteCreateSynced(scopeID, item.ID, detail.Version)
		return true
	}
	return false
}

func (s *Service) replay(scopeID string, item store.OutboxItem) bool {
	switch item.Entity {
	case "note":
		switch item.Op {
		case "create":
			return s.replayCreate(scopeID, item)
		case "update":
			return s.replayUpdate(scopeID, item)
		}
		var meta struct {
			Kind string `json:"kind"`
		}
		_ = json.Unmarshal([]byte(item.Payload), &meta)
		path := "/desktop/notes/" + item.ID
		if meta.Kind == "POST" {
			path = "/desktop/posts/" + item.ID
		}
		resp, err := s.api.Do(api.Request{Method: "DELETE", Path: path})
		if err != nil {
			return false
		}
		if resp.Status == 204 || resp.Status == 404 {
			_ = s.db.MarkNoteDeleteSynced(scopeID, item.ID)
			return true
		}
		return false
	case "folder":
		path := "/desktop/note-folders/" + item.ID
		if item.Op == "delete" {
			resp, err := s.api.Do(api.Request{Method: "DELETE", Path: path})
			return err == nil && (resp.Status == 204 || resp.Status == 404)
		}
		resp, err := s.api.Do(api.Request{Method: "PUT", Path: path, Body: item.Payload})
		if err != nil {
			return false
		}
		if resp.Status == 200 {
			_ = s.db.MarkFolderSynced(scopeID, item.ID)
			return true
		}
		return false
	}
	return true
}

func (s *Service) pull(scopeID, kind string) (int, error) {
	cursor := ""
	total := 0
	for page := 0; page < 200; page++ {
		path := "/desktop/" + kind + "/sync?limit=500"
		if cursor != "" {
			path += "&cursor=" + url.QueryEscape(cursor)
		}
		var result syncPage
		if err := s.api.Get(path, &result); err != nil {
			return total, err
		}

		rows := make([]store.RemoteNote, 0, len(result.Items))
		for _, item := range result.Items {
			rows = append(rows, store.RemoteNote{
				ID:            item.ID,
				Kind:          item.Kind,
				Title:         item.Title,
				FolderID:      item.FolderID,
				Pinned:        item.Pinned,
				PublishStatus: item.PublishStatus,
				Version:       item.Version,
				UpdatedAt:     parseTime(item.UpdateTime),
				Deleted:       item.Deleted,
			})
		}
		if err := s.db.ApplyRemoteNotes(scopeID, rows); err != nil {
			return total, err
		}
		total += len(rows)

		if !result.HasMore || result.NextCursor == "" {
			break
		}
		cursor = result.NextCursor
	}
	return total, nil
}

func parseTime(raw string) int64 {
	if raw == "" {
		return 0
	}
	if parsed, err := time.Parse(time.RFC3339Nano, raw); err == nil {
		return parsed.UnixMilli()
	}
	if parsed, err := time.Parse(time.RFC3339, raw); err == nil {
		return parsed.UnixMilli()
	}
	return 0
}
