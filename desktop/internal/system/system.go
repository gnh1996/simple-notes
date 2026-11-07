// Package system 暴露桌面端运行信息，供前端诊断 / 设置页使用。
package system

import (
	"fmt"
	"net/url"
	"os"
	"strings"

	"github.com/wailsapp/wails/v3/pkg/application"

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
	LoggedIn     bool   `json:"loggedIn"`
	ScopeID      string `json:"scopeId"`
	ServerURL    string `json:"serverUrl"`
	Username     string `json:"username"`
	Nickname     string `json:"nickname"`
}

// Service 通过 Wails binding 暴露系统信息服务。
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
	status := Status{
		Version:      Version,
		APIBaseURL:   s.baseURL,
		DatabasePath: s.db.Path(),
		HasToken:     err == nil && hasToken,
	}
	if stored, ok, err := s.db.BaseURL(); err == nil && ok && stored != "" {
		status.APIBaseURL = stored
	}
	if scope, ok, err := s.db.ActiveScope(); err == nil && ok {
		status.LoggedIn = status.HasToken
		status.ScopeID = scope.ScopeID
		status.ServerURL = scope.ServerURL
		status.Username = scope.Username
		status.Nickname = scope.Nickname
	}
	return status
}

// GetUIState 读取本机界面状态（标签顺序 / 侧栏宽度等）；不存在返回空串。
func (s *Service) GetUIState(key string) (string, error) {
	value, _, err := s.db.GetUIState(key)
	return value, err
}

// SetUIState 写入本机界面状态；不参与同步 / 导出。
func (s *Service) SetUIState(key, value string) error {
	return s.db.SetUIState(key, value)
}

// SetTitle 更新窗口标题（当前标签标题 + 未保存标记）。
func (s *Service) SetTitle(title string) error {
	app := application.Get()
	if app == nil {
		return nil
	}
	window := app.Window.Current()
	if window == nil {
		return nil
	}
	title = strings.TrimSpace(title)
	if title == "" {
		title = "Simple Notes"
	}
	window.SetTitle(title)
	return nil
}

// AttachWindowHandle 注册主窗口原生句柄获取器，由 main 在创建窗口后调用。
// 首帧主题下发早于 Wails 显示窗口，绑定回调需要直接拿到 HWND（Windows）。
func AttachWindowHandle(provider func() uintptr) {
	attachWindowHandle(provider)
}

// SetWindowTheme 让原生窗口标题栏 / 边框跟随应用明暗主题。
// Wails 启动时只按系统主题设置一次，手动切换主题后由前端调用本方法重新下发。
func (s *Service) SetWindowTheme(dark bool) error {
	applyWindowTheme(dark)
	return nil
}

// OpenAdmin 在系统浏览器打开管理端页面（发布 / 下架留在 admin）。
// 管理端默认按 <server>/admin 推导，可用 SIMPLE_NOTES_ADMIN_BASE 覆盖部署形态。
func (s *Service) OpenAdmin(path string) error {
	base := s.baseURL
	if stored, ok, err := s.db.BaseURL(); err == nil && ok && stored != "" {
		base = stored
	}
	adminBase := strings.TrimRight(os.Getenv("SIMPLE_NOTES_ADMIN_BASE"), "/")
	if adminBase == "" {
		adminBase = strings.TrimSuffix(strings.TrimRight(base, "/"), "/api") + "/admin"
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	target := adminBase + path
	parsed, err := url.Parse(target)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return fmt.Errorf("管理端地址不合法: %s", target)
	}
	return application.Get().Browser.OpenURL(target)
}
