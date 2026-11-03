// Package notes 负责本地索引查询、打开即拉正文与编辑推送（M1）。
package notes

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/media"
	"cc.naihao/simplenotes/desktop/internal/store"
)

// 虚拟文件夹：由桌面端本地维护，不写入 folder_local、不参与同步。
const (
	// LocalFilesFolderID 是内置「本地文件」分组的保留 folder_id。
	LocalFilesFolderID = store.LocalFilesFolderID
	// UncategorizedFolderID 是「未分类」筛选：folder_id 为空。
	UncategorizedFolderID = store.UncategorizedFolderID
)

// Service 通过 Wails binding 暴露列表 / 打开 / 保存能力。
type Service struct {
	db    *store.Store
	api   *api.Service
	media *media.Service
}

// New 创建内容服务。
func New(db *store.Store, apiService *api.Service, mediaService ...*media.Service) *Service {
	service := &Service{db: db, api: apiService}
	if len(mediaService) > 0 {
		service.media = mediaService[0]
	}
	return service
}

// CreateInput 是新建笔记 / 文章的本地请求。
type CreateInput struct {
	Kind     string `json:"kind"`
	Title    string `json:"title"`
	Content  string `json:"content"`
	FolderID string `json:"folderId"`
	// Target 为空时沿用旧行为（登录后落服务端，否则本地）；"local" 强制仅本地。
	Target string `json:"target"`
}

// ImportInput 是「纳入管理」请求：临时文件内容 → 本地 / 服务端笔记。
type ImportInput struct {
	Title    string `json:"title"`
	Content  string `json:"content"`
	Target   string `json:"target"` // local | server
	FolderID string `json:"folderId"`
	// SourcePath 是临时编辑文件路径；用于把相对图片复制 / 上传到目标存储。
	SourcePath string `json:"sourcePath"`
}

// SaveInput 是编辑器提交的本地保存请求。
type SaveInput struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Content  string `json:"content"`
	FolderID string `json:"folderId"`
}

// SaveResult 是一次保存 / 冲突处理的结果。
type SaveResult struct {
	State         string          `json:"state"` // synced | conflict | offline | error
	Note          *store.NoteRow  `json:"note"`
	Message       string          `json:"message"`
	RemoteTitle   string          `json:"remoteTitle"`
	RemoteVersion int64           `json:"remoteVersion"`
	Remote        *RemoteSnapshot `json:"remote"`
}

// RemoteSnapshot 是冲突时服务端那一侧的完整内容（用于 diff）。
type RemoteSnapshot struct {
	Title    string   `json:"title"`
	Content  string   `json:"content"`
	Tags     []string `json:"tags"`
	FolderID string   `json:"folderId"`
	Pinned   bool     `json:"pinned"`
	Version  int64    `json:"version"`
}

// DeleteResult 是删除 / 删除冲突处理的结果。
type DeleteResult struct {
	State   string `json:"state"` // deleted | copied | offline | error
	Message string `json:"message"`
}

type tagBrief struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type noteDetail struct {
	ID         string     `json:"id"`
	Title      string     `json:"title"`
	Content    string     `json:"content"`
	FolderID   string     `json:"folderId"`
	Tags       []tagBrief `json:"tags"`
	Pinned     bool       `json:"pinned"`
	Version    int64      `json:"version"`
	UpdateTime string     `json:"updateTime"`
}

type postDetail struct {
	noteDetail
	Summary               string `json:"summary"`
	Slug                  string `json:"slug"`
	CoverAttachmentID     string `json:"coverAttachmentId"`
	AuthoredAt            string `json:"authoredAt"`
	PublishStatus         string `json:"publishStatus"`
	HasUnpublishedChanges bool   `json:"hasUnpublishedChanges"`
}

type remoteDetail struct {
	Title        string
	Content      string
	FolderID     string
	Tags         []string
	Pinned       bool
	Version      int64
	PublishState string
	HasChanges   bool

	Summary    string
	Slug       string
	CoverID    string
	AuthoredAt string
}

func (d remoteDetail) applyTo(row *store.NoteRow) {
	row.Title = d.Title
	row.Content = d.Content
	row.ContentLoaded = true
	row.FolderID = d.FolderID
	row.Pinned = d.Pinned
	row.PublishStatus = d.PublishState
	row.HasUnpublishedChanges = d.HasChanges
	row.Tags = d.Tags
	row.RemoteVersion = d.Version
}

// List 返回本地索引；kind 为空时返回全部（NOTE / POST）。
// 无 active scope 时只返回纯本地笔记（免登录模式）。
func (s *Service) List(kind, folderID, keyword string) ([]store.NoteRow, error) {
	scope, hasScope, err := s.db.ActiveScope()
	if err != nil {
		return nil, err
	}

	rows := make([]store.NoteRow, 0, 32)
	localFolder, err := s.db.LocalFolderExists(folderID)
	if err != nil {
		return nil, err
	}
	// 服务端 scope：筛选本地文件夹时跳过；其余文件夹只查服务端。
	if hasScope && !localFolder {
		active, err := s.listScope(scope.ScopeID, kind, folderID, keyword)
		if err != nil {
			return nil, err
		}
		rows = append(rows, active...)
	}

	// 本地 scope：全部 / 未分类 / 本地文件夹时参与；筛选服务端文件夹时不混入本地笔记。
	if folderID == "" || folderID == UncategorizedFolderID || localFolder {
		local, err := s.listScope("local", kind, folderID, keyword)
		if err != nil {
			return nil, err
		}
		for i := range local {
			local[i].LocalOnly = true
		}
		rows = append(rows, local...)
	}

	sort.SliceStable(rows, func(i, j int) bool {
		if rows[i].Pinned != rows[j].Pinned {
			return rows[i].Pinned
		}
		return rows[i].UpdatedAt > rows[j].UpdatedAt
	})

	for i := range rows {
		rows[i].Conflict = !rows[i].LocalOnly && rows[i].Dirty &&
			(rows[i].RemoteVersion != rows[i].BaseVersion || rows[i].RemoteDeleted)
	}
	return rows, nil
}

func (s *Service) listScope(scopeID, kind, folderID, keyword string) ([]store.NoteRow, error) {
	if kind != "" {
		return s.db.ListNotes(scopeID, kind, folderID, keyword)
	}
	notes, errNotes := s.db.ListNotes(scopeID, "NOTE", folderID, keyword)
	posts, errPosts := s.db.ListNotes(scopeID, "POST", folderID, keyword)
	return append(notes, posts...), errors.Join(errNotes, errPosts)
}

// Folders 返回文件夹列表：内置「全部 / 未分类 / 本地文件」+ 本地自建 + 服务端镜像，并带上各自数量。
func (s *Service) Folders() ([]store.FolderRow, error) {
	scope, hasScope, err := s.db.ActiveScope()
	if err != nil {
		return nil, err
	}
	localCounts, err := s.db.CountNotesByFolder("local")
	if err != nil {
		return nil, err
	}
	activeCounts := map[string]int{}
	if hasScope {
		activeCounts, err = s.db.CountNotesByFolder(scope.ScopeID)
		if err != nil {
			return nil, err
		}
	}

	total := 0
	for _, count := range localCounts {
		total += count
	}
	for _, count := range activeCounts {
		total += count
	}

	localFolders, err := s.db.ListFolders("local")
	if err != nil {
		return nil, err
	}
	for i := range localFolders {
		localFolders[i].Local = true
		localFolders[i].NoteCount = localCounts[localFolders[i].ID]
	}

	out := make([]store.FolderRow, 0, 4+len(localFolders)+len(activeCounts))
	out = append(out,
		store.FolderRow{ID: "", Name: "全部笔记", Sort: -102, NoteCount: total},
		store.FolderRow{
			ID: UncategorizedFolderID, Name: "未分类", Sort: -101,
			NoteCount: localCounts[""] + activeCounts[""],
		},
		store.FolderRow{
			ID: LocalFilesFolderID, Name: store.LocalFilesFolderName, Sort: -100,
			NoteCount: localCounts[LocalFilesFolderID], Local: true,
		},
	)
	out = append(out, localFolders...)
	if hasScope {
		folders, err := s.db.ListFolders(scope.ScopeID)
		if err != nil {
			return nil, err
		}
		for i := range folders {
			folders[i].NoteCount = activeCounts[folders[i].ID]
		}
		out = append(out, folders...)
	}
	return out, nil
}

// Open 返回笔记内容：已缓存且版本未变（或本地有未推送修改）时直接读本地，否则拉详情。
func (s *Service) Open(id string) (*store.NoteRow, error) {
	scopeID, row, found, err := s.db.GetNoteAny(id)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, errors.New("本地索引中没有这篇内容，请先同步")
	}
	if scopeID == "local" {
		row.LocalOnly = true
		return &row, nil
	}
	if row.ContentLoaded && (row.BaseVersion == row.RemoteVersion || row.Dirty) {
		row.Conflict = row.Dirty && (row.RemoteVersion != row.BaseVersion || row.RemoteDeleted)
		return &row, nil
	}

	detail, err := s.fetchDetail(row.Kind, id)
	if err != nil {
		return nil, err
	}
	detail.applyTo(&row)
	tagsJSON, _ := json.Marshal(row.Tags)
	hash := ContentHash(row)
	if err := s.db.SaveNoteDetail(scopeID, row, string(tagsJSON), hash); err != nil {
		return nil, fmt.Errorf("缓存正文: %w", err)
	}
	row.BaseVersion = detail.Version
	row.Conflict = false
	return &row, nil
}

// OpenCached 只读本地缓存，不触网；用于启动恢复标签 / 避免首屏等待网络。
// 未缓存正文时仍返回索引行（ContentLoaded=false），由前端显示占位。
func (s *Service) OpenCached(id string) (*store.NoteRow, error) {
	scopeID, row, found, err := s.db.GetNoteAny(id)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, errors.New("本地索引中没有这篇内容")
	}
	if scopeID == "local" {
		row.LocalOnly = true
		return &row, nil
	}
	row.Conflict = row.Dirty && (row.RemoteVersion != row.BaseVersion || row.RemoteDeleted)
	return &row, nil
}

// Save 先写本地（dirty），再按 base_version 推送；纯本地笔记只落本地。
func (s *Service) Save(input SaveInput) (*SaveResult, error) {
	scopeID, row, found, err := s.db.GetNoteAny(input.ID)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, errors.New("本地索引中没有这篇内容")
	}
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return nil, errors.New("标题不能为空")
	}

	row.Title = title
	row.Content = input.Content
	if scopeID != "local" && s.media != nil && media.HasLocalRefs(row.Content) {
		content, rewriteErr := s.media.RewriteLocalRefsToServer(row.Content)
		if rewriteErr != nil {
			return nil, fmt.Errorf("上传本地图片: %w", rewriteErr)
		}
		row.Content = content
	}
	row.ContentLoaded = true
	row.FolderID = s.normalizeFolderID(scopeID, input.FolderID)
	tagsJSON, _ := json.Marshal(row.Tags)
	hash := ContentHash(row)
	if err := s.db.SaveNoteLocal(scopeID, row, string(tagsJSON), hash); err != nil {
		return nil, fmt.Errorf("保存本地: %w", err)
	}
	if scopeID == "local" {
		_ = s.db.MarkLocalOnlySaved(scopeID, row.ID)
		row.Dirty = false
		row.LocalOnly = true
		if s.media != nil {
			_ = s.media.Cleanup()
		}
		return &SaveResult{State: "synced", Note: &row, Message: "本地笔记已保存"}, nil
	}
	result, err := s.pushAndApply(scopeID, row, row.BaseVersion, hash)
	if err == nil && result != nil && result.State == "synced" && s.media != nil {
		_ = s.media.Cleanup()
	}
	return result, err
}

// Resolve 处理冲突：local = 以本地为准（强制覆盖），remote = 以服务端为准；被覆盖的一侧留副本。
func (s *Service) Resolve(id, side string) (*SaveResult, error) {
	scopeID, row, found, err := s.db.GetNoteAny(id)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, errors.New("本地索引中没有这篇内容")
	}
	if scopeID == "local" {
		return nil, errors.New("纯本地笔记没有云端冲突")
	}
	detail, err := s.fetchDetail(row.Kind, id)
	if err != nil {
		return nil, err
	}

	if side == "remote" {
		_ = s.copyConflictSide(scopeID, row)
		detail.applyTo(&row)
		tagsJSON, _ := json.Marshal(row.Tags)
		hash := ContentHash(row)
		if err := s.db.SaveNoteDetail(scopeID, row, string(tagsJSON), hash); err != nil {
			return nil, err
		}
		row.BaseVersion = detail.Version
		row.Conflict = false
		return &SaveResult{State: "synced", Note: &row, Message: "已采用云端版本，本地修改保留为副本"}, nil
	}

	_ = s.copyRemoteSide(scopeID, row.Kind, detail)
	hash := ContentHash(row)
	return s.pushAndApply(scopeID, row, detail.Version, hash)
}

// copyConflictSide 把本地一侧复制到 local scope（选择服务端时调用）。
func (s *Service) copyConflictSide(scopeID string, row store.NoteRow) error {
	_, err := s.db.CopyNoteLocal(scopeID, row, "local")
	return err
}

// copyRemoteSide 把服务端一侧复制到 local scope（选择本地时调用）。
func (s *Service) copyRemoteSide(scopeID, kind string, detail remoteDetail) error {
	remoteRow := store.NoteRow{
		Kind:          kind,
		Title:         detail.Title,
		Content:       detail.Content,
		FolderID:      detail.FolderID,
		Pinned:        detail.Pinned,
		Tags:          detail.Tags,
		ContentLoaded: true,
	}
	_, err := s.db.CopyNoteLocal(scopeID, remoteRow, "local")
	return err
}

// Create 离线新建：本地先生成 UUID 并写入，再尝试推送；未登录时只写本地。
func (s *Service) Create(input CreateInput) (*SaveResult, error) {
	scope, hasScope, err := s.db.ActiveScope()
	if err != nil {
		return nil, err
	}
	kind := input.Kind
	if kind != "POST" || !hasScope || input.Target == "local" {
		kind = "NOTE"
	}
	title := strings.TrimSpace(input.Title)
	if title == "" {
		title = "未命名"
	}
	scopeID := "local"
	folderID := ""
	forceLocal := input.Target == "local"
	if hasScope && !forceLocal {
		scopeID = scope.ScopeID
		folderID = s.normalizeFolderID(scopeID, input.FolderID)
	} else {
		folderID = s.normalizeFolderID("local", input.FolderID)
	}
	row := store.NoteRow{
		ID:            store.NewUUID(),
		Kind:          kind,
		Title:         title,
		Content:       input.Content,
		FolderID:      folderID,
		Tags:          []string{},
		ContentLoaded: true,
		UpdatedAt:     time.Now().UnixMilli(),
	}
	tagsJSON, _ := json.Marshal(row.Tags)
	hash := ContentHash(row)
	if err := s.db.InsertNoteLocal(scopeID, row, string(tagsJSON), hash); err != nil {
		return nil, fmt.Errorf("保存本地: %w", err)
	}
	if !hasScope || forceLocal {
		_ = s.db.MarkLocalOnlySaved(scopeID, row.ID)
		row.Dirty = false
		row.LocalOnly = true
		return &SaveResult{State: "synced", Note: &row, Message: "本地笔记已保存"}, nil
	}
	return s.pushCreate(scopeID, row, hash)
}

// pushCreate 把一条已写入本地的笔记推到服务端；失败入 outbox。
func (s *Service) pushCreate(scopeID string, row store.NoteRow, hash string) (*SaveResult, error) {
	body := map[string]any{
		"id":       row.ID,
		"title":    row.Title,
		"content":  row.Content,
		"tags":     row.Tags,
		"pinned":   false,
		"folderId": nullableString(row.FolderID),
	}
	raw, _ := json.Marshal(body)
	path := "/desktop/notes"
	if row.Kind == "POST" {
		path = "/desktop/posts"
	}
	resp, err := s.api.Do(api.Request{Method: http.MethodPost, Path: path, Body: string(raw)})
	if err != nil {
		s.enqueueCreate(scopeID, row.Kind, body)
		return &SaveResult{State: "offline", Note: &row, Message: "已离线保存，联网后自动创建"}, nil
	}
	if resp.Status == http.StatusOK || resp.Status == http.StatusCreated {
		version := parseVersion(resp.Body)
		_ = s.db.MarkNoteSynced(scopeID, row.ID, version, hash)
		row.BaseVersion = version
		row.RemoteVersion = version
		row.Dirty = false
		return &SaveResult{State: "synced", Note: &row, Message: "已创建"}, nil
	}
	if resp.Status == http.StatusConflict {
		if detail, fetchErr := s.fetchDetail(row.Kind, row.ID); fetchErr == nil {
			_ = s.db.MarkNoteSynced(scopeID, row.ID, detail.Version, hash)
			row.BaseVersion = detail.Version
			row.RemoteVersion = detail.Version
			row.Dirty = false
			return &SaveResult{State: "synced", Note: &row, Message: "已创建"}, nil
		}
	}
	if resp.Status == http.StatusUnauthorized || resp.Status >= 500 {
		s.enqueueCreate(scopeID, row.Kind, body)
		return &SaveResult{State: "offline", Note: &row, Message: "已离线保存，联网后自动创建"}, nil
	}
	return &SaveResult{State: "error", Note: &row, Message: api.ErrorMessage(resp.Status, resp.Body)}, nil
}

// ImportFile 把临时编辑内容纳入管理：仅本地（内置「本地文件」）或服务端。
func (s *Service) ImportFile(input ImportInput) (*SaveResult, error) {
	title := strings.TrimSpace(input.Title)
	if title == "" {
		title = "未命名"
	}
	if utf8.RuneCountInString(title) > 200 {
		return nil, errors.New("标题不能超过 200 个字符")
	}

	content := input.Content
	if s.media != nil {
		var err error
		if input.Target == "server" {
			content, err = s.media.RewriteFileRefsToServer(content, input.SourcePath)
		} else {
			content, err = s.media.RewriteFileRefsToLocal(content, input.SourcePath)
		}
		if err != nil {
			return nil, fmt.Errorf("处理图片: %w", err)
		}
	}

	row := store.NoteRow{
		ID:            store.NewUUID(),
		Kind:          "NOTE",
		Title:         title,
		Content:       content,
		Tags:          []string{},
		ContentLoaded: true,
		UpdatedAt:     time.Now().UnixMilli(),
	}

	if input.Target != "server" {
		row.FolderID = LocalFilesFolderID
		if input.FolderID != "" && input.FolderID != LocalFilesFolderID {
			row.FolderID = s.normalizeFolderID("local", input.FolderID)
		}
		tagsJSON, _ := json.Marshal(row.Tags)
		hash := ContentHash(row)
		if err := s.db.InsertNoteLocal("local", row, string(tagsJSON), hash); err != nil {
			return nil, fmt.Errorf("保存本地: %w", err)
		}
		_ = s.db.MarkLocalOnlySaved("local", row.ID)
		row.Dirty = false
		row.LocalOnly = true
		return &SaveResult{State: "synced", Note: &row, Message: "已保存为本地笔记"}, nil
	}

	scope, ok, err := s.db.ActiveScope()
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errors.New("请先登录后再同步到云端")
	}
	folderID := s.normalizeFolderID(scope.ScopeID, input.FolderID)
	row.FolderID = folderID
	tagsJSON, _ := json.Marshal(row.Tags)
	hash := ContentHash(row)
	if err := s.db.InsertNoteLocal(scope.ScopeID, row, string(tagsJSON), hash); err != nil {
		return nil, fmt.Errorf("保存本地: %w", err)
	}
	res, err := s.pushCreate(scope.ScopeID, row, hash)
	if err != nil {
		return nil, err
	}
	return s.fallbackToLocal(scope.ScopeID, res), nil
}

// fallbackToLocal 在服务端明确拒绝创建时把笔记退回「本地文件」，避免留下无法保存的脏行。
func (s *Service) fallbackToLocal(scopeID string, result *SaveResult) *SaveResult {
	if result == nil || result.State != "error" || result.Note == nil {
		return result
	}
	if err := s.db.MoveNoteScope(scopeID, "local", result.Note.ID, LocalFilesFolderID); err != nil {
		return result
	}
	_ = s.db.MarkLocalOnlySaved("local", result.Note.ID)
	result.Note.LocalOnly = true
	result.Note.FolderID = LocalFilesFolderID
	result.Note.Dirty = false
	result.Message = "云端创建失败，已保留为本地笔记：" + result.Message
	return result
}

// PromoteLocal 把仅本地笔记迁移到服务端 scope（保留 id，替换文件夹）。
func (s *Service) PromoteLocal(id, folderID string) (*SaveResult, error) {
	scope, ok, err := s.db.ActiveScope()
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errors.New("请先登录后再同步到云端")
	}
	scopeID, row, found, err := s.db.GetNoteAny(id)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, errors.New("本地索引中没有这篇内容")
	}
	if scopeID != "local" {
		return nil, errors.New("只有纯本地笔记可以同步到云端")
	}
	if !row.ContentLoaded {
		return nil, errors.New("笔记正文尚未加载")
	}
	content := row.Content
	if s.media != nil {
		content, err = s.media.RewriteLocalRefsToServer(content)
		if err != nil {
			return nil, fmt.Errorf("上传本地图片: %w", err)
		}
	}
	folderID = s.normalizeFolderID(scope.ScopeID, folderID)
	if err := s.db.MoveNoteScope("local", scope.ScopeID, id, folderID); err != nil {
		return nil, fmt.Errorf("迁移本地笔记: %w", err)
	}
	row.Content = content
	row.FolderID = folderID
	row.Dirty = true
	row.LocalOnly = false
	hash := ContentHash(row)
	res, err := s.pushCreate(scope.ScopeID, row, hash)
	if err != nil {
		return nil, err
	}
	result := s.fallbackToLocal(scope.ScopeID, res)
	if result != nil && result.State == "synced" && s.media != nil {
		_ = s.media.Cleanup()
	}
	return result, nil
}

func (s *Service) enqueueCreate(scopeID, kind string, body map[string]any) {
	payload, _ := json.Marshal(map[string]any{"kind": kind, "body": body})
	_ = s.db.Enqueue(scopeID, "note", body["id"].(string), "create", string(payload))
}

func parseVersion(raw string) int64 {
	var detail noteDetail
	if err := json.Unmarshal([]byte(raw), &detail); err == nil {
		return detail.Version
	}
	var post postDetail
	if err := json.Unmarshal([]byte(raw), &post); err == nil {
		return post.Version
	}
	return 0
}

// Delete 本地删除：墓碑 + 立即推送；失败写入 outbox 等待重试。
func (s *Service) Delete(id string) (*DeleteResult, error) {
	scopeID, row, found, err := s.db.GetNoteAny(id)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, errors.New("本地索引中没有这篇内容")
	}
	if scopeID == "local" {
		_ = s.db.DeleteNoteLocal(scopeID, id)
		if s.media != nil {
			_ = s.media.Cleanup()
		}
		return &DeleteResult{State: "deleted", Message: "本地笔记已删除"}, nil
	}
	if err := s.db.MarkNoteDeletedLocal(scopeID, id); err != nil {
		return nil, err
	}

	path := "/desktop/notes/" + id
	if row.Kind == "POST" {
		path = "/desktop/posts/" + id
	}
	resp, err := s.api.Do(api.Request{Method: http.MethodDelete, Path: path})
	if err != nil {
		payload, _ := json.Marshal(map[string]string{"kind": row.Kind})
		_ = s.db.Enqueue(scopeID, "note", id, "delete", string(payload))
		return &DeleteResult{State: "offline", Message: "已本地删除，联网后自动同步"}, nil
	}
	if resp.Status == http.StatusNoContent || resp.Status == http.StatusNotFound {
		_ = s.db.MarkNoteDeleteSynced(scopeID, id)
		return &DeleteResult{State: "deleted", Message: "已删除"}, nil
	}
	if resp.Status == http.StatusUnauthorized || resp.Status >= 500 {
		payload, _ := json.Marshal(map[string]string{"kind": row.Kind})
		_ = s.db.Enqueue(scopeID, "note", id, "delete", string(payload))
		return &DeleteResult{State: "offline", Message: "已本地删除，联网后自动同步"}, nil
	}
	return &DeleteResult{State: "error", Message: api.ErrorMessage(resp.Status, resp.Body)}, nil
}

// ResolveDelete 处理「服务端已删除 + 本地有修改」：local = 保留为纯本地副本，remote = 放弃本地。
func (s *Service) ResolveDelete(id, side string) (*DeleteResult, error) {
	scopeID, row, found, err := s.db.GetNoteAny(id)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, errors.New("本地索引中没有这篇内容")
	}
	if scopeID == "local" {
		_ = s.db.DeleteNoteLocal(scopeID, id)
		return &DeleteResult{State: "deleted", Message: "本地笔记已删除"}, nil
	}
	if side == "local" {
		if !row.ContentLoaded {
			if _, err := s.Open(id); err != nil {
				return nil, err
			}
			row, _, _ = s.db.GetNote(scopeID, id)
		}
		if _, err := s.db.CopyNoteLocal(scopeID, row, "local"); err != nil {
			return nil, fmt.Errorf("保留本地副本: %w", err)
		}
		_ = s.db.DeleteNoteLocal(scopeID, id)
		return &DeleteResult{State: "copied", Message: "已保留为本地副本"}, nil
	}
	_ = s.db.DeleteNoteLocal(scopeID, id)
	return &DeleteResult{State: "deleted", Message: "已放弃本地修改"}, nil
}

func (s *Service) pushAndApply(scopeID string, row store.NoteRow, version int64, hash string) (*SaveResult, error) {
	state, message, newVersion, err := s.push(row, version)
	if err != nil {
		// 网络 / 传输错误：保留 dirty，下次继续推送。
		row.Dirty = true
		row.Conflict = row.RemoteVersion != row.BaseVersion
		return &SaveResult{State: "offline", Note: &row, Message: err.Error()}, nil
	}
	switch state {
	case "ok":
		if err := s.db.MarkNoteSynced(scopeID, row.ID, newVersion, hash); err != nil {
			return nil, err
		}
		row.BaseVersion = newVersion
		row.RemoteVersion = newVersion
		row.Dirty = false
		row.Conflict = false
		return &SaveResult{State: "synced", Note: &row, Message: "已同步"}, nil
	case "conflict":
		remote, fetchErr := s.fetchDetail(row.Kind, row.ID)
		result := &SaveResult{
			State:   "conflict",
			Note:    &row,
			Message: "云端已有新版本",
		}
		if fetchErr == nil {
			result.RemoteTitle = remote.Title
			result.RemoteVersion = remote.Version
			result.Remote = &RemoteSnapshot{
				Title:    remote.Title,
				Content:  remote.Content,
				Tags:     remote.Tags,
				FolderID: remote.FolderID,
				Pinned:   remote.Pinned,
				Version:  remote.Version,
			}
			// 版本不同但内容一致（例如 Web 端只改了发布状态 / 置顶）：静默对齐，不打扰用户。
			remoteRow := store.NoteRow{
				Title:    remote.Title,
				Content:  remote.Content,
				Tags:     remote.Tags,
				FolderID: remote.FolderID,
				Pinned:   remote.Pinned,
			}
			if ContentHash(remoteRow) == hash {
				_ = s.db.MarkNoteSynced(scopeID, row.ID, remote.Version, hash)
				row.BaseVersion = remote.Version
				row.RemoteVersion = remote.Version
				row.Dirty = false
				row.Conflict = false
				return &SaveResult{State: "synced", Note: &row, Message: "内容一致，已对齐版本"}, nil
			}
		}
		row.Dirty = true
		row.Conflict = true
		return result, nil
	default:
		return &SaveResult{State: "error", Note: &row, Message: message}, nil
	}
}

// push 调 PUT；返回 ok / conflict / error 与新的服务端版本。
func (s *Service) push(row store.NoteRow, version int64) (string, string, int64, error) {
	if row.Kind == "POST" {
		// 文章带 summary / slug / cover / authoredAt：先取当前值原样回传，避免误清空。
		var current postDetail
		if err := s.api.Get("/desktop/posts/"+row.ID, &current); err != nil {
			return "", "", 0, err
		}
		payload := map[string]any{
			"version":    version,
			"title":      row.Title,
			"content":    row.Content,
			"tags":       row.Tags,
			"pinned":     row.Pinned,
			"summary":    current.Summary,
			"slug":       current.Slug,
			"authoredAt": current.AuthoredAt,
		}
		if current.CoverAttachmentID != "" {
			payload["coverAttachmentId"] = current.CoverAttachmentID
		}
		body, _ := json.Marshal(payload)
		resp, err := s.api.Do(api.Request{Method: http.MethodPut, Path: "/desktop/posts/" + row.ID, Body: string(body)})
		if err != nil {
			return "", "", 0, err
		}
		switch resp.Status {
		case http.StatusOK:
			var detail postDetail
			if err := json.Unmarshal([]byte(resp.Body), &detail); err != nil {
				return "", "", 0, fmt.Errorf("解析响应: %w", err)
			}
			return "ok", "", detail.Version, nil
		case http.StatusConflict:
			return "conflict", "", 0, nil
		default:
			return "error", api.ErrorMessage(resp.Status, resp.Body), 0, nil
		}
	}

	payload := map[string]any{
		"version":  version,
		"title":    row.Title,
		"content":  row.Content,
		"tags":     row.Tags,
		"pinned":   row.Pinned,
		"folderId": nullableString(row.FolderID),
	}
	body, _ := json.Marshal(payload)
	resp, err := s.api.Do(api.Request{Method: http.MethodPut, Path: "/desktop/notes/" + row.ID, Body: string(body)})
	if err != nil {
		return "", "", 0, err
	}
	switch resp.Status {
	case http.StatusOK:
		var detail noteDetail
		if err := json.Unmarshal([]byte(resp.Body), &detail); err != nil {
			return "", "", 0, fmt.Errorf("解析响应: %w", err)
		}
		return "ok", "", detail.Version, nil
	case http.StatusConflict:
		return "conflict", "", 0, nil
	default:
		return "error", api.ErrorMessage(resp.Status, resp.Body), 0, nil
	}
}

func (s *Service) fetchDetail(kind, id string) (remoteDetail, error) {
	if kind == "POST" {
		var post postDetail
		if err := s.api.Get("/desktop/posts/"+id, &post); err != nil {
			return remoteDetail{}, err
		}
		return remoteDetail{
			Title:        post.Title,
			Content:      post.Content,
			FolderID:     post.FolderID,
			Tags:         sortedTagNames(post.Tags),
			Pinned:       post.Pinned,
			Version:      post.Version,
			PublishState: post.PublishStatus,
			HasChanges:   post.HasUnpublishedChanges,
			Summary:      post.Summary,
			Slug:         post.Slug,
			CoverID:      post.CoverAttachmentID,
			AuthoredAt:   post.AuthoredAt,
		}, nil
	}
	var detail noteDetail
	if err := s.api.Get("/desktop/notes/"+id, &detail); err != nil {
		return remoteDetail{}, err
	}
	return remoteDetail{
		Title:    detail.Title,
		Content:  detail.Content,
		FolderID: detail.FolderID,
		Tags:     sortedTagNames(detail.Tags),
		Pinned:   detail.Pinned,
		Version:  detail.Version,
	}, nil
}

func sortedTagNames(tags []tagBrief) []string {
	seen := make(map[string]struct{}, len(tags))
	names := make([]string, 0, len(tags))
	for _, tag := range tags {
		name := strings.TrimSpace(tag.Name)
		if name == "" {
			continue
		}
		if _, ok := seen[name]; ok {
			continue
		}
		seen[name] = struct{}{}
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

func nullableString(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}

// normalizeFolderID 把前端传来的归类收敛到当前 scope 的合法值：
// 本地 scope 只接受内置「本地文件」或已存在的本地文件夹；服务端 scope
// 一律清掉本地专用 id（未分类 / 本地文件 / 本地自建文件夹）。
func (s *Service) normalizeFolderID(scopeID, folderID string) string {
	isLocal, err := s.db.LocalFolderExists(folderID)
	if err != nil {
		isLocal = false
	}
	if scopeID == "local" {
		if isLocal {
			return folderID
		}
		return ""
	}
	if folderID == "" || folderID == UncategorizedFolderID || isLocal {
		return ""
	}
	return folderID
}

// ContentHash 计算同步字段的规范化指纹（docs/desktop-sync.md §4）：
// 正文统一 LF，标签已排序，字段以 NUL 分隔，避免拼接歧义。
func ContentHash(row store.NoteRow) string {
	tags, _ := json.Marshal(row.Tags)
	h := sha256.New()
	write := func(value string) {
		h.Write([]byte(value))
		h.Write([]byte{0})
	}
	write(row.Title)
	write(strings.ReplaceAll(row.Content, "\r\n", "\n"))
	write(string(tags))
	write(row.FolderID)
	if row.Pinned {
		write("1")
	} else {
		write("0")
	}
	return hex.EncodeToString(h.Sum(nil))
}
