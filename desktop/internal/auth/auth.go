// Package auth 负责桌面端登录 / 登出与 scope 切换。
//
// 令牌只写本地 SQLite（docs/auth-rbac.md §6.4），不返回给前端；
// 前端拿到的只是会话摘要（用户 / 服务器 / scope）。
package auth

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"cc.naihao/simplenotes/desktop/internal/store"
)

// Service 通过 Wails binding 暴露登录能力。
type Service struct {
	db     *store.Store
	client *http.Client
}

// LoginInput 是登录表单。
type LoginInput struct {
	ServerURL string `json:"serverUrl"`
	Username  string `json:"username"`
	Password  string `json:"password"`
}

// Session 是前端可见的会话摘要（不含令牌）。
type Session struct {
	ScopeID   string `json:"scopeId"`
	ServerURL string `json:"serverUrl"`
	UserID    string `json:"userId"`
	Username  string `json:"username"`
	Nickname  string `json:"nickname"`
}

// New 创建认证服务。
func New(db *store.Store) *Service {
	return &Service{
		db:     db,
		client: &http.Client{Timeout: 15 * time.Second},
	}
}

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
	Client   string `json:"client"`
}

type loginResponse struct {
	Token string `json:"token"`
	User  struct {
		ID       string `json:"id"`
		Username string `json:"username"`
		Nickname string `json:"nickname"`
	} `json:"user"`
}

// Login 校验服务器地址、登录并落库；成功返回会话摘要。
func (s *Service) Login(input LoginInput) (*Session, error) {
	base, err := NormalizeBaseURL(input.ServerURL)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(input.Username) == "" {
		return nil, errors.New("用户名不能为空")
	}
	if input.Password == "" {
		return nil, errors.New("密码不能为空")
	}

	payload, err := json.Marshal(loginRequest{
		Username: strings.TrimSpace(input.Username),
		Password: input.Password,
		Client:   "DESKTOP",
	})
	if err != nil {
		return nil, fmt.Errorf("构造登录请求: %w", err)
	}
	req, err := http.NewRequest(http.MethodPost, base+"/auth/login", bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("构造登录请求: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := s.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("连接服务器失败: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("读取登录响应: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s", serverMessage(resp.StatusCode, body))
	}

	var result loginResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("解析登录响应: %w", err)
	}
	if result.Token == "" || result.User.ID == "" {
		return nil, errors.New("登录响应缺少令牌或用户信息")
	}

	scope := store.Scope{
		ScopeID:   ScopeID(base, result.User.ID),
		ServerURL: base,
		UserID:    result.User.ID,
		Username:  result.User.Username,
		Nickname:  result.User.Nickname,
	}
	if err := s.db.SetBaseURL(base); err != nil {
		return nil, fmt.Errorf("保存服务器地址: %w", err)
	}
	if err := s.db.SetToken(result.Token); err != nil {
		return nil, fmt.Errorf("保存会话: %w", err)
	}
	if err := s.db.SaveScope(scope); err != nil {
		return nil, fmt.Errorf("保存账号信息: %w", err)
	}
	return &Session{
		ScopeID:   scope.ScopeID,
		ServerURL: base,
		UserID:    scope.UserID,
		Username:  scope.Username,
		Nickname:  scope.Nickname,
	}, nil
}

// Current 返回当前会话摘要；未登录返回 nil。
func (s *Service) Current() (*Session, error) {
	scope, ok, err := s.db.ActiveScope()
	if err != nil || !ok {
		return nil, err
	}
	token, hasToken, err := s.db.Token()
	if err != nil || !hasToken || token == "" {
		return nil, err
	}
	return &Session{
		ScopeID:   scope.ScopeID,
		ServerURL: scope.ServerURL,
		UserID:    scope.UserID,
		Username:  scope.Username,
		Nickname:  scope.Nickname,
	}, nil
}

// Logout 撤销服务端会话（尽力而为）并清空本地令牌；数据保留但隐藏。
func (s *Service) Logout() error {
	base, hasBase, err := s.db.BaseURL()
	if err == nil && hasBase {
		if token, ok, _ := s.db.Token(); ok && token != "" {
			req, reqErr := http.NewRequest(http.MethodPost, base+"/auth/logout", nil)
			if reqErr == nil {
				req.Header.Set("Authorization", "Bearer "+token)
				if resp, doErr := s.client.Do(req); doErr == nil {
					io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
					resp.Body.Close()
				}
			}
		}
	}
	if err := s.db.ClearToken(); err != nil {
		return err
	}
	return s.db.DeactivateScopes()
}

// NormalizeBaseURL 接受 host[:port] 或完整 URL，统一为 scheme://host[:port]/api。
func NormalizeBaseURL(raw string) (string, error) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return "", errors.New("服务器地址不能为空")
	}
	if !strings.Contains(value, "://") {
		value = "http://" + value
	}
	parsed, err := url.Parse(value)
	if err != nil {
		return "", errors.New("服务器地址格式不正确")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", errors.New("服务器地址只支持 http / https")
	}
	if parsed.Host == "" {
		return "", errors.New("服务器地址缺少主机名")
	}
	if parsed.User != nil {
		return "", errors.New("服务器地址不能包含用户名或密码")
	}
	if parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", errors.New("服务器地址不能包含查询参数或片段")
	}

	path := strings.TrimRight(parsed.Path, "/")
	if path == "" {
		path = "/api"
	} else if !strings.HasSuffix(path, "/api") {
		path += "/api"
	}
	return parsed.Scheme + "://" + parsed.Host + path, nil
}

// ScopeID 生成稳定的 scope 标识：同一服务器 + 账号得到同一个 id。
func ScopeID(baseURL, userID string) string {
	sum := sha256.Sum256([]byte(baseURL))
	return "srv:" + hex.EncodeToString(sum[:])[:12] + ":user:" + userID
}

func serverMessage(status int, body []byte) string {
	var envelope struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if json.Unmarshal(body, &envelope) == nil && envelope.Message != "" {
		return envelope.Message
	}
	if status == http.StatusUnauthorized {
		return "用户名或密码错误"
	}
	return fmt.Sprintf("登录失败（HTTP %d）", status)
}
