// Package api 是桌面端到后端的唯一 HTTP 通道。
//
// 设计要点（docs/tech-eval-vditor-wails.md §3.3）：
//   - WebView 不直接 fetch 后端：避免自定义 scheme 的 CORS，也让 JWT 不进入 JS；
//   - 前端仍使用 OpenAPI 生成的 TS 类型（packages/api），这里只做通用转发；
//   - 不做 DTO 映射，响应体原样返回 JSON 字符串，由前端按生成类型解析。
package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"cc.naihao/simplenotes/desktop/internal/logging"
	"cc.naihao/simplenotes/desktop/internal/store"
)

// ErrServerTooOld 表示服务端不认识 desktop 接口（通常是对端版本较旧）。
// 见 docs/desktop-local-schema.md §8.5：未知端点不当作泛化错误。
var ErrServerTooOld = errors.New("云端版本较低，请升级云端服务")

// Request 是一次转发请求。Body 为 JSON 字符串（为空表示无请求体）。
type Request struct {
	Method string `json:"method"`
	Path   string `json:"path"`
	Body   string `json:"body,omitempty"`
}

// Response 是后端原始响应。
type Response struct {
	Status int    `json:"status"`
	Body   string `json:"body"`
}

// Service 通过 Wails binding 暴露给前端。
type Service struct {
	db        *store.Store
	baseURL   string
	client    *http.Client
	reachable atomic.Bool
}

// New 创建转发服务。baseURL 形如 http://localhost:8080/api。
func New(db *store.Store, baseURL string) *Service {
	service := &Service{
		db:      db,
		baseURL: strings.TrimRight(baseURL, "/"),
		client:  &http.Client{Timeout: 30 * time.Second},
	}
	service.reachable.Store(true)
	return service
}

// Reachable 返回最近一次请求是否成功连上后端（任意 HTTP 状态都算连通）。
func (s *Service) Reachable() bool {
	return s.reachable.Load()
}

// BaseURL 返回当前后端地址：优先使用设置页保存的值，其次回落到环境变量 / 默认值。
func (s *Service) BaseURL() string {
	if value, ok, err := s.db.BaseURL(); err == nil && ok && value != "" {
		return value
	}
	return s.baseURL
}

// Get 发起 GET 并把 2xx JSON 响应解码到 out；非 2xx 转成错误（含服务端 message）。
func (s *Service) Get(path string, out any) error {
	return s.get(path, out, false)
}

// GetEndpoint 用于「路径本身必须存在」的请求（如 manifest / 文件夹列表）：
// 404 / 405 说明服务端没有这个接口，按「服务端过旧」处理，而不是「资源不存在」。
func (s *Service) GetEndpoint(path string, out any) error {
	return s.get(path, out, true)
}

func (s *Service) get(path string, out any, endpoint bool) error {
	resp, err := s.Do(Request{Method: http.MethodGet, Path: path})
	if err != nil {
		return err
	}
	if endpoint && (resp.Status == http.StatusNotFound || resp.Status == http.StatusMethodNotAllowed) {
		return ErrServerTooOld
	}
	if resp.Status < 200 || resp.Status >= 300 {
		return fmt.Errorf("%s", errorMessage(resp.Status, resp.Body))
	}
	if err := json.Unmarshal([]byte(resp.Body), out); err != nil {
		return fmt.Errorf("解析响应: %w", err)
	}
	return nil
}

// Do 转发一次请求；HTTP 非 2xx 也会正常返回（由前端按状态码处理）。
func (s *Service) Do(req Request) (*Response, error) {
	method := strings.ToUpper(strings.TrimSpace(req.Method))
	if method == "" {
		method = http.MethodGet
	}
	if !strings.HasPrefix(req.Path, "/") || strings.Contains(req.Path, "://") {
		return nil, fmt.Errorf("非法路径: %q", req.Path)
	}

	var body io.Reader
	if req.Body != "" {
		body = strings.NewReader(req.Body)
	}

	httpReq, err := http.NewRequest(method, s.BaseURL()+req.Path, body)
	if err != nil {
		return nil, fmt.Errorf("构造请求: %w", err)
	}
	httpReq.Header.Set("Accept", "application/json")
	if req.Body != "" {
		httpReq.Header.Set("Content-Type", "application/json")
	}
	if token, ok, err := s.db.Token(); err != nil {
		return nil, fmt.Errorf("读取会话: %w", err)
	} else if ok && token != "" {
		httpReq.Header.Set("Authorization", "Bearer "+token)
	}

	started := time.Now()
	resp, err := s.client.Do(httpReq)
	if err != nil {
		s.reachable.Store(false)
		errText := err.Error()
		// 网络错误会带完整 URL；去掉查询串，避免把搜索关键词写进日志。
		if httpReq.URL.RawQuery != "" {
			errText = strings.ReplaceAll(errText, "?"+httpReq.URL.RawQuery, "?…")
		}
		logging.Warn("api.request", "请求后端失败", map[string]string{
			"method":      method,
			"path":        logPath(req.Path),
			"duration_ms": fmt.Sprintf("%d", time.Since(started).Milliseconds()),
			"err":         errText,
		})
		return nil, fmt.Errorf("请求后端失败: %w", err)
	}
	defer resp.Body.Close()
	s.reachable.Store(true)

	// 通用转发只承载 JSON；附件走独立的上传 / 下载通道。
	data, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, fmt.Errorf("读取响应: %w", err)
	}
	logResponse(method, req.Path, resp.StatusCode, started)
	return &Response{Status: resp.StatusCode, Body: string(data)}, nil
}

// logResponse 只记录可排查的请求元数据；不落查询参数与响应体（可能含笔记内容）。
func logResponse(method, path string, status int, started time.Time) {
	fields := map[string]string{
		"method":      method,
		"path":        logPath(path),
		"status":      fmt.Sprintf("%d", status),
		"duration_ms": fmt.Sprintf("%d", time.Since(started).Milliseconds()),
	}
	switch {
	case status >= 500:
		logging.Error("api.request", "服务端返回错误", fields)
	case status == 401 || status == 403 || status == 409:
		logging.Warn("api.request", "请求被拒绝", fields)
	default:
		logging.Debug("api.request", "请求完成", fields)
	}
}

// logPath 去掉查询串：搜索关键词等可能包含用户内容。
func logPath(path string) string {
	if index := strings.IndexByte(path, '?'); index >= 0 {
		return path[:index]
	}
	return path
}

// ErrorMessage 把接口错误体转成可展示文案（供其它 service 复用）。
func ErrorMessage(status int, body string) string {
	return errorMessage(status, body)
}

// errorMessage 把接口错误体转成可展示文案。
func errorMessage(status int, body string) string {
	var envelope struct {
		Message string `json:"message"`
	}
	if json.Unmarshal([]byte(body), &envelope) == nil && envelope.Message != "" {
		return envelope.Message
	}
	if status == http.StatusUnauthorized {
		return "会话已失效，请重新登录"
	}
	return fmt.Sprintf("请求失败（HTTP %d）", status)
}
