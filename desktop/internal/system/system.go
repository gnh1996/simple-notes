// Package system 暴露桌面端运行信息，供前端诊断 / 设置页使用。
package system

import (
	"cc.naihao/simplenotes/desktop/internal/store"
)

// Version 为桌面端版本；发布时由构建参数覆盖。
var Version = "0.1.0"

// Status 是前端可见的运行状态。
type Status struct {
	Version      string `json:"version"`
	APIBaseURL   string `json:"apiBaseUrl"`
	DatabasePath string `json:"databasePath"`
	HasToken     bool   `json:"hasToken"`
}

// Service 通过 Wails binding 暴露给前端。
type Service struct {
	db      *store.Store
	baseURL string
}

// New 创建系统信息服务。
func New(db *store.Store, baseURL string) *Service {
	return &Service{db: db, baseURL: baseURL}
}

// Status 返回当前运行状态；令牌只报告是否存在，不回传明文。
func (s *Service) Status() Status {
	_, hasToken, err := s.db.Token()
	return Status{
		Version:      Version,
		APIBaseURL:   s.baseURL,
		DatabasePath: s.db.Path(),
		HasToken:     err == nil && hasToken,
	}
}
