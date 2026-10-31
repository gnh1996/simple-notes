// Package api 是桌面端到后端的唯一 HTTP 通道。
//
// 设计要点（docs/tech-eval-vditor-wails.md §3.3）：
//   - WebView 不直接 fetch 后端：避免自定义 scheme 的 CORS，也让 JWT 不进入 JS；
//   - 前端仍使用 OpenAPI 生成的 TS 类型（packages/api），这里只做通用转发；
//   - 不做 DTO 映射，响应体原样返回 JSON 字符串，由前端按生成类型解析。
package api

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"cc.naihao/simplenotes/desktop/internal/store"
)

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
	db      *store.Store
	baseURL string
	client  *http.Client
}

// New 创建转发服务。baseURL 形如 http://localhost:8080/api。
func New(db *store.Store, baseURL string) *Service {
	return &Service{
		db:      db,
		baseURL: strings.TrimRight(baseURL, "/"),
		client:  &http.Client{Timeout: 30 * time.Second},
	}
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
	resp, err := s.Do(Request{Method: http.MethodGet, Path: path})
	if err != nil {
		return err
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

	resp, err := s.client.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("请求后端失败: %w", err)
	}
	defer resp.Body.Close()

	// 通用转发只承载 JSON；附件走独立的上传 / 下载通道。
	data, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, fmt.Errorf("读取响应: %w", err)
	}
	return &Response{Status: resp.StatusCode, Body: string(data)}, nil
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
