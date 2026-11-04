// Package logging 是桌面端运行日志与诊断信息的唯一出口。
//
// 设计见 docs/desktop-diagnostics.md：
//   - 用户可见错误与诊断详情分离，日志只记录操作名与可排查字段；
//   - 令牌 / 密码 / 笔记正文 / 请求体不落盘，敏感字段统一打码；
//   - 单文件按大小轮转，失败不阻塞应用（诊断能力不能反过来影响主流程）。
package logging

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

const (
	logFileName    = "desktop.log"
	maxMessageLen  = 4096
	maxFieldLen    = 512
	recentLimit    = 100
	defaultMaxSize = 5 << 20
	defaultBackups = 4
)

// Level 是日志级别；数值越大越严重。
type Level int

const (
	LevelDebug Level = iota
	LevelInfo
	LevelWarn
	LevelError
)

func (l Level) String() string {
	switch l {
	case LevelDebug:
		return "debug"
	case LevelWarn:
		return "warn"
	case LevelError:
		return "error"
	default:
		return "info"
	}
}

func parseLevel(raw string) Level {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "debug":
		return LevelDebug
	case "warn", "warning":
		return LevelWarn
	case "error":
		return LevelError
	default:
		return LevelInfo
	}
}

// Entry 是一条结构化日志，也是「最近错误」缓冲区的元素。
type Entry struct {
	Time    string            `json:"time"`
	Level   string            `json:"level"`
	Event   string            `json:"event"`
	Message string            `json:"message,omitempty"`
	Fields  map[string]string `json:"fields,omitempty"`
}

// Service 通过 Wails binding 暴露给前端，同时供 Go 侧各包写日志。
type Service struct {
	mu         sync.Mutex
	dir        string
	path       string
	version    string
	file       *os.File
	size       int64
	maxSize    int64
	maxBackups int
	minLevel   Level
	recent     []Entry
	openErr    error
}

var defaultService *Service

// Init 初始化默认日志服务；目录优先使用用户缓存目录（不参与漫游同步）。
// 返回的 error 只表示日志文件不可写，调用方应继续启动应用。
func Init(version string) (*Service, error) {
	base, err := os.UserCacheDir()
	if err != nil {
		base, err = os.UserConfigDir()
	}
	if err != nil {
		service := &Service{version: version, minLevel: LevelInfo, maxSize: defaultMaxSize, maxBackups: defaultBackups}
		defaultService = service
		return service, err
	}
	service, err := NewAt(filepath.Join(base, "simple-notes", "logs"), version)
	if service != nil {
		service.SetMinLevel(parseLevel(os.Getenv("SIMPLE_NOTES_LOG_LEVEL")))
	}
	defaultService = service
	return service, err
}

// NewAt 在指定目录创建日志服务；目录或文件不可写时仍返回可用的内存服务。
func NewAt(dir, version string) (*Service, error) {
	service := &Service{
		dir:        dir,
		path:       filepath.Join(dir, logFileName),
		version:    version,
		maxSize:    defaultMaxSize,
		maxBackups: defaultBackups,
		minLevel:   LevelInfo,
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		service.openErr = err
		return service, err
	}
	service.openErr = service.openLocked()
	return service, service.openErr
}

// Default 返回初始化后的默认服务；未初始化时为 nil。
func Default() *Service { return defaultService }

// SetMinLevel 设置默认服务的最低记录级别（测试 / 调试用）。
func (s *Service) SetMinLevel(level Level) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.minLevel = level
}

// Close 关闭日志文件；可重复调用。
func (s *Service) Close() error {
	if s == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file == nil {
		return nil
	}
	err := s.file.Close()
	s.file = nil
	return err
}

// Write 是前端调用的日志入口。日志失败不回传错误，避免前端因写日志失败再报错。
func (s *Service) Write(level, event, message string, fields map[string]string) error {
	s.log(parseLevel(level), event, message, fields)
	return nil
}

// LogDir 返回日志目录，供前端展示 / 复制。
func (s *Service) LogDir() string {
	if s == nil {
		return ""
	}
	return s.dir
}

// LogPath 返回当前日志文件路径。
func (s *Service) LogPath() string {
	if s == nil {
		return ""
	}
	return s.path
}

// Recent 返回最近的日志（新的在后），用于诊断详情。
func (s *Service) Recent(limit int) []Entry {
	if s == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if limit <= 0 || limit > len(s.recent) {
		limit = len(s.recent)
	}
	out := make([]Entry, limit)
	copy(out, s.recent[len(s.recent)-limit:])
	return out
}

// Diagnostics 生成可复制的诊断摘要（不含令牌 / 正文）。
func (s *Service) Diagnostics() string {
	if s == nil {
		return ""
	}
	s.mu.Lock()
	dir, path, version, openErr := s.dir, s.path, s.version, s.openErr
	recent := make([]Entry, len(s.recent))
	copy(recent, s.recent)
	s.mu.Unlock()

	var builder strings.Builder
	fmt.Fprintf(&builder, "Simple Notes %s\n", version)
	fmt.Fprintf(&builder, "OS: %s/%s\n", runtime.GOOS, runtime.GOARCH)
	fmt.Fprintf(&builder, "Log: %s\n", path)
	fmt.Fprintf(&builder, "Log dir: %s\n", dir)
	fmt.Fprintf(&builder, "Generated: %s\n", time.Now().Format(time.RFC3339))
	if openErr != nil {
		fmt.Fprintf(&builder, "Log unavailable: %v\n", openErr)
	}
	fmt.Fprintf(&builder, "Recent entries: %d\n", len(recent))
	for _, entry := range recent {
		line, err := json.Marshal(entry)
		if err == nil {
			builder.Write(line)
			builder.WriteByte('\n')
		}
	}
	return builder.String()
}

// OpenLogDir 在系统文件管理器中打开日志目录。
func (s *Service) OpenLogDir() error {
	if s == nil {
		return fmt.Errorf("日志服务未初始化")
	}
	if err := os.MkdirAll(s.dir, 0o700); err != nil {
		return fmt.Errorf("创建日志目录: %w", err)
	}
	var command *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		command = exec.Command("explorer", s.dir)
	case "darwin":
		command = exec.Command("open", s.dir)
	default:
		command = exec.Command("xdg-open", s.dir)
	}
	if err := command.Start(); err != nil {
		return fmt.Errorf("打开日志目录: %w", err)
	}
	return nil
}

// Debug 记录调试日志。
func Debug(event, message string, fields map[string]string) {
	defaultService.log(LevelDebug, event, message, fields)
}

// Info 记录普通信息。
func Info(event, message string, fields map[string]string) {
	defaultService.log(LevelInfo, event, message, fields)
}

// Warn 记录可恢复异常（网络失败等）。
func Warn(event, message string, fields map[string]string) {
	defaultService.log(LevelWarn, event, message, fields)
}

// Error 记录需要排查的异常。
func Error(event, message string, fields map[string]string) {
	defaultService.log(LevelError, event, message, fields)
}

func (s *Service) log(level Level, event, message string, fields map[string]string) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if level < s.minLevel {
		return
	}
	entry := Entry{
		Time:    time.Now().Format(time.RFC3339Nano),
		Level:   level.String(),
		Event:   truncate(strings.TrimSpace(event), 120),
		Message: truncate(strings.TrimSpace(message), maxMessageLen),
		Fields:  sanitizeFields(fields),
	}
	line, err := json.Marshal(entry)
	if err != nil {
		return
	}

	s.recent = append(s.recent, entry)
	if len(s.recent) > recentLimit {
		s.recent = append([]Entry(nil), s.recent[len(s.recent)-recentLimit:]...)
	}
	if s.file == nil {
		return
	}
	n, writeErr := s.file.Write(append(line, '\n'))
	if writeErr == nil {
		s.size += int64(n)
	}
	if s.size >= s.maxSize {
		s.rotateLocked()
	}
}

func (s *Service) openLocked() error {
	file, err := os.OpenFile(s.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return err
	}
	s.file = file
	s.size = info.Size()
	return nil
}

// rotateLocked 关闭当前文件并按 desktop.log.1 ... desktop.log.N 轮转。
// Windows 下必须先关闭句柄才能重命名。
func (s *Service) rotateLocked() {
	if s.file != nil {
		_ = s.file.Close()
		s.file = nil
	}
	oldest := fmt.Sprintf("%s.%d", s.path, s.maxBackups)
	_ = os.Remove(oldest)
	for i := s.maxBackups - 1; i >= 1; i-- {
		from := fmt.Sprintf("%s.%d", s.path, i)
		to := fmt.Sprintf("%s.%d", s.path, i+1)
		_ = os.Rename(from, to)
	}
	_ = os.Rename(s.path, s.path+".1")
	_ = s.openLocked()
}

func sanitizeFields(fields map[string]string) map[string]string {
	if len(fields) == 0 {
		return nil
	}
	out := make(map[string]string, len(fields))
	count := 0
	for key, value := range fields {
		key = strings.TrimSpace(key)
		if key == "" {
			continue
		}
		if count >= 20 {
			out["truncated"] = "true"
			break
		}
		count++
		if isSensitive(key) {
			out[key] = "***"
			continue
		}
		out[key] = truncate(strings.TrimSpace(value), maxFieldLen)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func isSensitive(key string) bool {
	switch strings.ToLower(strings.TrimSpace(key)) {
	case "token", "access_token", "refresh_token", "password", "passwd", "pwd",
		"secret", "authorization", "cookie", "set-cookie", "body", "content",
		"markdown", "text", "note", "title":
		return true
	default:
		return false
	}
}

func truncate(value string, limit int) string {
	if limit <= 0 {
		return value
	}
	// 按 rune 截断，避免把 UTF-8 字符截成无效字节。
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit]) + "…"
}
