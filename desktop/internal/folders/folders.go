// Package folders 负责桌面端文件夹的本地管理与云端镜像写操作（v2.0 树形）。
package folders

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"unicode/utf8"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/store"
)

const (
	// maxFolderNameLength 与云端 NoteFolderRequest 的 @Size(max = 64) 对齐。
	maxFolderNameLength = 64
	// maxFolderDepth 与云端一致：根为第 1 级，最多 5 级。
	maxFolderDepth = 5
	// localScopeID 是桌面端纯本地数据的 scope。
	localScopeID = "local"
)

// Service 通过 Wails binding 暴露文件夹能力。
type Service struct {
	db  *store.Store
	api *api.Service
}

// New 创建文件夹服务。
func New(db *store.Store, apiService *api.Service) *Service {
	return &Service{db: db, api: apiService}
}

// Result 是写操作结果。
type Result struct {
	State   string           `json:"state"` // ok | offline | error
	Folder  *store.FolderRow `json:"folder"`
	Message string           `json:"message"`
}

type folderPayload struct {
	ID       string `json:"id"`
	ParentID string `json:"parentId"`
	Name     string `json:"name"`
	Sort     int    `json:"sort"`
}

// List 返回当前账号的文件夹镜像；未登录 / 免登录模式返回空列表。
func (s *Service) List() ([]store.FolderRow, error) {
	scope, ok, err := s.active()
	if err != nil {
		return nil, err
	}
	if !ok {
		return []store.FolderRow{}, nil
	}
	return s.db.ListFolders(scope.ScopeID)
}

// Create 新建文件夹：target='local' 时只写本地库（离线可用、永不参与同步）；
// 否则在线新建云端文件夹（云端生成 id，离线不支持）。
func (s *Service) Create(name, target, parentID string) (*Result, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("文件夹名称不能为空")
	}
	if utf8.RuneCountInString(name) > maxFolderNameLength {
		return nil, fmt.Errorf("文件夹名称最长 %d 个字符", maxFolderNameLength)
	}
	if target == "local" {
		return s.createLocal(name, parentID)
	}

	scope, ok, err := s.active()
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errors.New("请先登录")
	}
	parent, err := s.requireDepth(scope.ScopeID, parentID)
	if err != nil {
		return nil, err
	}
	body, _ := json.Marshal(map[string]any{
		"name": name, "sort": 0, "parentId": nullableID(parent),
	})
	resp, err := s.api.Do(api.Request{Method: http.MethodPost, Path: "/desktop/note-folders", Body: string(body)})
	if err != nil {
		return &Result{State: "offline", Message: "离线时不能新建文件夹，请恢复网络后重试"}, nil
	}
	if resp.Status != http.StatusOK && resp.Status != http.StatusCreated {
		return &Result{State: "error", Message: api.ErrorMessage(resp.Status, resp.Body)}, nil
	}
	var created folderPayload
	if err := json.Unmarshal([]byte(resp.Body), &created); err != nil {
		return nil, fmt.Errorf("解析响应: %w", err)
	}
	created.ParentID = parent
	if err := s.db.UpsertRemoteFolders(scope.ScopeID, []store.FolderRow{{
		ID: created.ID, ParentID: created.ParentID, Name: created.Name, Sort: created.Sort,
	}}); err != nil {
		return nil, err
	}
	return &Result{State: "ok", Folder: &store.FolderRow{
		ID: created.ID, ParentID: created.ParentID, Name: created.Name, Sort: created.Sort,
	}}, nil
}

// createLocal 新建本地文件夹：本地生成 id，不触网、不写 outbox。
func (s *Service) createLocal(name, parentID string) (*Result, error) {
	parent, err := s.requireDepth(localScopeID, parentID)
	if err != nil {
		return nil, err
	}
	taken, err := s.db.LocalFolderNameTaken(name, parentID, "")
	if err != nil {
		return nil, err
	}
	if taken {
		return &Result{State: "error", Message: "已存在同名文件夹"}, nil
	}
	id := store.NewUUID()
	if err := s.db.SaveLocalFolder(id, name, 0, parent); err != nil {
		return nil, fmt.Errorf("保存本地文件夹: %w", err)
	}
	return &Result{
		State: "ok", Message: "已创建本地文件夹",
		Folder: &store.FolderRow{ID: id, ParentID: parent, Name: name, Local: true},
	}, nil
}

// Rename 改名：本地文件夹只改本地库；云端文件夹本地改名后推送，失败进 outbox。
func (s *Service) Rename(id, name string) (*Result, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("文件夹名称不能为空")
	}
	if utf8.RuneCountInString(name) > maxFolderNameLength {
		return nil, fmt.Errorf("文件夹名称最长 %d 个字符", maxFolderNameLength)
	}

	localFolder, isLocal, err := s.db.GetFolder(localScopeID, id)
	if err != nil {
		return nil, err
	}
	if isLocal {
		taken, err := s.db.LocalFolderNameTaken(name, localFolder.ParentID, id)
		if err != nil {
			return nil, err
		}
		if taken {
			return &Result{State: "error", Message: "已存在同名文件夹"}, nil
		}
		if err := s.db.SaveLocalFolder(id, name, localFolder.Sort, localFolder.ParentID); err != nil {
			return nil, fmt.Errorf("保存本地文件夹: %w", err)
		}
		return &Result{State: "ok", Message: "已保存"}, nil
	}

	scope, ok, err := s.active()
	if err != nil || !ok {
		if err == nil {
			err = errors.New("请先登录")
		}
		return nil, err
	}
	folders, err := s.db.ListFolders(scope.ScopeID)
	if err != nil {
		return nil, err
	}
	sort, parentID := 0, ""
	for _, folder := range folders {
		if folder.ID == id {
			sort, parentID = folder.Sort, folder.ParentID
			break
		}
	}
	if err := s.db.SaveFolderLocal(scope.ScopeID, id, name, sort, parentID); err != nil {
		return nil, err
	}
	payload, _ := json.Marshal(map[string]any{"name": name, "sort": sort})
	resp, err := s.api.Do(api.Request{Method: http.MethodPut, Path: "/desktop/note-folders/" + id, Body: string(payload)})
	if err != nil {
		_ = s.db.Enqueue(scope.ScopeID, "folder", id, "rename", string(payload))
		return &Result{State: "offline", Message: "已本地改名，联网后自动同步"}, nil
	}
	if resp.Status == http.StatusOK {
		_ = s.db.MarkFolderSynced(scope.ScopeID, id)
		return &Result{State: "ok", Message: "已同步"}, nil
	}
	if resp.Status == http.StatusUnauthorized || resp.Status >= 500 {
		_ = s.db.Enqueue(scope.ScopeID, "folder", id, "rename", string(payload))
		return &Result{State: "offline", Message: "已本地改名，联网后自动同步"}, nil
	}
	return &Result{State: "error", Message: api.ErrorMessage(resp.Status, resp.Body)}, nil
}

// Delete 删除：有子文件夹时拒绝；本地文件夹删除叶子时笔记落未分类，云端文件夹本地删除后推送。
func (s *Service) Delete(id string) (*Result, error) {
	if _, isLocal, err := s.db.GetFolder(localScopeID, id); err != nil {
		return nil, err
	} else if isLocal {
		hasChildren, err := s.db.FolderHasChildren(localScopeID, id)
		if err != nil {
			return nil, err
		}
		if hasChildren {
			return &Result{State: "error", Message: "请先删除子文件夹"}, nil
		}
		if err := s.db.DeleteLocalFolder(id); err != nil {
			return nil, err
		}
		return &Result{State: "ok", Message: "已删除，笔记已移到未分类"}, nil
	}

	scope, ok, err := s.active()
	if err != nil || !ok {
		if err == nil {
			err = errors.New("请先登录")
		}
		return nil, err
	}
	hasChildren, err := s.db.FolderHasChildren(scope.ScopeID, id)
	if err != nil {
		return nil, err
	}
	if hasChildren {
		return &Result{State: "error", Message: "请先删除子文件夹"}, nil
	}
	if err := s.db.DeleteFolderLocal(scope.ScopeID, id); err != nil {
		return nil, err
	}
	resp, err := s.api.Do(api.Request{Method: http.MethodDelete, Path: "/desktop/note-folders/" + id})
	if err != nil {
		_ = s.db.Enqueue(scope.ScopeID, "folder", id, "delete", "")
		return &Result{State: "offline", Message: "已本地删除，联网后自动同步"}, nil
	}
	if resp.Status == http.StatusNoContent || resp.Status == http.StatusNotFound {
		return &Result{State: "ok", Message: "已删除"}, nil
	}
	if resp.Status == http.StatusUnauthorized || resp.Status >= 500 {
		_ = s.db.Enqueue(scope.ScopeID, "folder", id, "delete", "")
		return &Result{State: "offline", Message: "已本地删除，联网后自动同步"}, nil
	}
	return &Result{State: "error", Message: api.ErrorMessage(resp.Status, resp.Body)}, nil
}

// requireDepth 校验父文件夹存在且新文件夹不超过最大深度，返回规范化的 parentId。
func (s *Service) requireDepth(scopeID, parentID string) (string, error) {
	if parentID == "" {
		return "", nil
	}
	if _, ok, err := s.db.GetFolder(scopeID, parentID); err != nil {
		return "", err
	} else if !ok {
		return "", errors.New("父文件夹不存在")
	}
	depth, err := s.folderDepth(scopeID, parentID)
	if err != nil {
		return "", err
	}
	if depth+1 > maxFolderDepth {
		return "", fmt.Errorf("文件夹最多支持 %d 级", maxFolderDepth)
	}
	return parentID, nil
}

func (s *Service) folderDepth(scopeID, id string) (int, error) {
	depth := 0
	cursor := id
	for guard := 0; cursor != "" && guard < maxFolderDepth; guard++ {
		folder, ok, err := s.db.GetFolder(scopeID, cursor)
		if err != nil {
			return 0, err
		}
		if !ok {
			break
		}
		depth++
		cursor = folder.ParentID
	}
	return depth, nil
}

func (s *Service) active() (store.Scope, bool, error) {
	scope, ok, err := s.db.ActiveScope()
	return scope, ok, err
}

func nullableID(id string) *string {
	if id == "" {
		return nil
	}
	return &id
}
