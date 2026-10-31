// Package folders 负责桌面端文件夹的本地镜像与写操作（M2）。
package folders

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/store"
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
	ID         string `json:"id"`
	Name       string `json:"name"`
	Sort       int    `json:"sort"`
	UpdateTime string `json:"updateTime"`
}

// List 返回本地文件夹镜像；未登录 / 免登录模式返回空列表。
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

// Create 在线新建文件夹（离线不支持：服务端 id 无法预生成）。
func (s *Service) Create(name string) (*Result, error) {
	scope, ok, err := s.active()
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errors.New("请先登录")
	}
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("文件夹名称不能为空")
	}
	body, _ := json.Marshal(map[string]any{"name": name, "sort": 0})
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
	if err := s.db.UpsertRemoteFolders(scope.ScopeID, []store.FolderRow{{
		ID: created.ID, Name: created.Name, Sort: created.Sort,
	}}); err != nil {
		return nil, err
	}
	return &Result{State: "ok", Folder: &store.FolderRow{ID: created.ID, Name: created.Name, Sort: created.Sort}}, nil
}

// Rename 本地改名后推送；失败进 outbox。
func (s *Service) Rename(id, name string) (*Result, error) {
	scope, ok, err := s.active()
	if err != nil || !ok {
		if err == nil {
			err = errors.New("请先登录")
		}
		return nil, err
	}
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, errors.New("文件夹名称不能为空")
	}
	folders, err := s.db.ListFolders(scope.ScopeID)
	if err != nil {
		return nil, err
	}
	sort := 0
	for _, folder := range folders {
		if folder.ID == id {
			sort = folder.Sort
			break
		}
	}
	if err := s.db.SaveFolderLocal(scope.ScopeID, id, name, sort); err != nil {
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

// Delete 本地删除后推送；失败进 outbox。
func (s *Service) Delete(id string) (*Result, error) {
	scope, ok, err := s.active()
	if err != nil || !ok {
		if err == nil {
			err = errors.New("请先登录")
		}
		return nil, err
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

func (s *Service) active() (store.Scope, bool, error) {
	scope, ok, err := s.db.ActiveScope()
	return scope, ok, err
}
