// Package localfile 负责桌面端本地 Markdown 文件的打开、保存、导出与最近记录。
//
// 设计见 docs/desktop-local-file.md：
//   - 打开文件默认是「临时编辑」，内容不写 SQLite（只记录最近路径）；
//   - 写回使用显式保存 + 内容 hash 冲突检测 + 原子写入；
//   - 导出只生成独立副本，不改变笔记，也不建立关联。
package localfile

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/wailsapp/wails/v3/pkg/application"

	"cc.naihao/simplenotes/desktop/internal/store"
)

const maxFileSize = 5 << 20 // 5 MB，与 docs/desktop-local-file.md 一致

// LocalFile 是打开后的临时文档快照；Hash 用于保存时的外部修改检测。
type LocalFile struct {
	Path       string `json:"path"`
	Name       string `json:"name"`
	Content    string `json:"content"`
	Hash       string `json:"hash"`
	MtimeMs    int64  `json:"mtimeMs"`
	Size       int64  `json:"size"`
	LineEnding string `json:"lineEnding"` // crlf | lf
	BOM        bool   `json:"bom"`
	// MediaToken 是临时文件图片目录在 Wails 资产服务中的不透明 token。
	MediaToken string `json:"mediaToken"`
}

// RecentFile 是前端展示的最近打开项；Exists 用于提示文件已被移动 / 删除。
type RecentFile struct {
	Path     string `json:"path"`
	Name     string `json:"name"`
	OpenedAt int64  `json:"openedAt"`
	Exists   bool   `json:"exists"`
}

// SaveFileInput 是显式保存请求。
type SaveFileInput struct {
	Path         string `json:"path"`
	Content      string `json:"content"`
	ExpectedHash string `json:"expectedHash"`
	Force        bool   `json:"force"`
}

// SaveFileResult 描述保存结果：saved / modified / missing / error。
type SaveFileResult struct {
	State      string `json:"state"`
	Path       string `json:"path"`
	Name       string `json:"name"`
	Hash       string `json:"hash"`
	MtimeMs    int64  `json:"mtimeMs"`
	Size       int64  `json:"size"`
	LineEnding string `json:"lineEnding"`
	Message    string `json:"message"`
}

// SaveAsInput 是「另存为」请求。
type SaveAsInput struct {
	DefaultName string `json:"defaultName"`
	Content     string `json:"content"`
	// SourcePath 用于把旧文件 assets 目录里的图片复制到新位置。
	SourcePath string `json:"sourcePath"`
}

// ExportInput 是「导出为 Markdown」请求。
type ExportInput struct {
	DefaultName string `json:"defaultName"`
	Content     string `json:"content"`
}

// ExportResult 是导出结果；Path 为空表示用户取消。
type ExportResult struct {
	Path string `json:"path"`
	Name string `json:"name"`
}

// MediaHooks 是本地文件与图片服务之间的最小接口，避免 localfile 依赖具体实现。
type MediaHooks interface {
	RegisterFileTab(path string) (string, error)
	ReleaseFileTab(token string)
	RewriteFileRefsForSaveAs(content, sourcePath, targetPath string) (string, error)
	RewriteLocalRefsToExport(content, targetPath string) (string, error)
}

// Service 通过 Wails binding 暴露本地文件能力。
type Service struct {
	db      *store.Store
	media   MediaHooks
	unsaved atomic.Bool
	closing atomic.Bool
}

// New 创建本地文件服务。
func New(db *store.Store, hooks ...MediaHooks) *Service {
	service := &Service{db: db}
	if len(hooks) > 0 {
		service.media = hooks[0]
	}
	return service
}

// SetUnsaved 由前端同步「是否有未保存内容」，供窗口关闭钩子判断。
func (s *Service) SetUnsaved(unsaved bool) {
	s.unsaved.Store(unsaved)
}

// ShouldBlockClose 返回是否应拦截本次窗口关闭。
func (s *Service) ShouldBlockClose() bool {
	return s.unsaved.Load() && !s.closing.Load()
}

// ConfirmClose 允许关闭并退出应用（前端处理完保存确认后调用）。
func (s *Service) ConfirmClose() {
	s.closing.Store(true)
	if app := application.Get(); app != nil {
		app.Quit()
	}
}

// OpenDialog 打开文件对话框；用户取消时返回 nil。
func (s *Service) OpenDialog() (*LocalFile, error) {
	path, err := application.Get().Dialog.OpenFile().
		AddFilter("Markdown", "*.md;*.markdown").
		SetTitle("打开 Markdown 文件").
		PromptForSingleSelection()
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(path) == "" {
		return nil, nil
	}
	return s.OpenPath(path)
}

// OpenPath 读取并校验指定路径（拖拽 / 最近打开 / 系统入口）。
func (s *Service) OpenPath(path string) (*LocalFile, error) {
	file, err := readLocalFile(path)
	if err != nil {
		return nil, err
	}
	if err := s.attachMediaToken(file); err != nil {
		return nil, err
	}
	s.touchRecent(file)
	return file, nil
}

func (s *Service) attachMediaToken(file *LocalFile) error {
	if s.media == nil {
		return nil
	}
	token, err := s.media.RegisterFileTab(file.Path)
	if err != nil {
		return fmt.Errorf("登记图片目录: %w", err)
	}
	file.MediaToken = token
	return nil
}

// Read 重新读取当前文件（不改变最近记录之外的任何状态）。
func (s *Service) Read(path string) (*LocalFile, error) {
	return s.OpenPath(path)
}

// Save 写回源文件。先比对内容 hash，外部修改时返回 modified，由前端确认覆盖。
func (s *Service) Save(input SaveFileInput) (*SaveFileResult, error) {
	current, err := readFileData(input.Path)
	if errors.Is(err, os.ErrNotExist) {
		return &SaveFileResult{State: "missing", Message: "文件已被删除，请使用「另存为」"}, nil
	}
	if err != nil {
		return nil, err
	}
	if !input.Force && input.ExpectedHash != "" && current.Hash != input.ExpectedHash {
		return &SaveFileResult{
			State:   "modified",
			Path:    current.Path,
			Name:    current.Name,
			Message: "文件已被其他程序修改",
		}, nil
	}

	data := encodeContent(input.Content, current.LineEnding, current.BOM)
	if len(data) > maxFileSize {
		return nil, fmt.Errorf("内容超过 %d MB，无法保存", maxFileSize>>20)
	}
	if err := writeAtomic(current.Path, data, current.Mode); err != nil {
		return &SaveFileResult{State: "error", Path: current.Path, Name: current.Name, Message: err.Error()}, nil
	}

	updated, err := readLocalFile(current.Path)
	if err != nil {
		return nil, fmt.Errorf("保存后重新读取: %w", err)
	}
	s.touchRecent(updated)
	return &SaveFileResult{
		State:      "saved",
		Path:       updated.Path,
		Name:       updated.Name,
		Hash:       updated.Hash,
		MtimeMs:    updated.MtimeMs,
		Size:       updated.Size,
		LineEnding: updated.LineEnding,
	}, nil
}

// SaveAs 另存为新的 Markdown 文件；取消时返回 nil。
func (s *Service) SaveAs(input SaveAsInput) (*LocalFile, error) {
	path, err := s.saveDialog(input.DefaultName)
	if err != nil || path == "" {
		return nil, err
	}
	content := input.Content
	if s.media != nil && input.SourcePath != "" {
		content, err = s.media.RewriteFileRefsForSaveAs(content, input.SourcePath, path)
		if err != nil {
			return nil, err
		}
	}
	if err := writeAtomic(path, encodeContent(content, "lf", false), 0o644); err != nil {
		return nil, err
	}
	file, err := readLocalFile(path)
	if err != nil {
		return nil, err
	}
	if err := s.attachMediaToken(file); err != nil {
		return nil, err
	}
	s.touchRecent(file)
	return file, nil
}

// ExportMarkdown 把已管理笔记导出为独立文件；取消时返回 nil。
func (s *Service) ExportMarkdown(input ExportInput) (*ExportResult, error) {
	path, err := s.saveDialog(input.DefaultName)
	if err != nil || path == "" {
		return nil, err
	}
	content := input.Content
	if s.media != nil {
		content, err = s.media.RewriteLocalRefsToExport(content, path)
		if err != nil {
			return nil, err
		}
	}
	if err := writeAtomic(path, encodeContent(content, "lf", false), 0o644); err != nil {
		return nil, err
	}
	return &ExportResult{Path: path, Name: filepath.Base(path)}, nil
}

// Recent 返回最近打开列表，并标记文件是否仍存在。
func (s *Service) Recent() ([]RecentFile, error) {
	rows, err := s.db.ListRecentFiles(20)
	if err != nil {
		return nil, err
	}
	out := make([]RecentFile, 0, len(rows))
	for _, row := range rows {
		_, statErr := os.Stat(row.Path)
		out = append(out, RecentFile{
			Path:     row.Path,
			Name:     row.Name,
			OpenedAt: row.OpenedAt,
			Exists:   statErr == nil,
		})
	}
	return out, nil
}

// RemoveRecent 移除一条最近记录。
func (s *Service) RemoveRecent(path string) error {
	return s.db.DeleteRecentFile(path)
}

// ClearRecent 清空最近记录。
func (s *Service) ClearRecent() error {
	return s.db.ClearRecentFiles()
}

func (s *Service) saveDialog(defaultName string) (string, error) {
	dialog := application.Get().Dialog.SaveFile()
	dialog.SetOptions(&application.SaveFileDialogOptions{Title: "保存 Markdown 文件"})
	path, err := dialog.
		AddFilter("Markdown", "*.md;*.markdown").
		SetFilename(defaultMarkdownName(defaultName)).
		PromptForSingleSelection()
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(path) == "" {
		return "", nil
	}
	if filepath.Ext(path) == "" {
		path += ".md"
	}
	return path, nil
}

func (s *Service) touchRecent(file *LocalFile) {
	_ = s.db.UpsertRecentFile(file.Path, file.Name, time.Now().UnixMilli())
}

type fileData struct {
	Path       string
	Name       string
	Content    string
	Hash       string
	MtimeMs    int64
	Size       int64
	LineEnding string
	BOM        bool
	Mode       os.FileMode
}

func readLocalFile(path string) (*LocalFile, error) {
	data, err := readFileData(path)
	if err != nil {
		return nil, err
	}
	return &LocalFile{
		Path:       data.Path,
		Name:       data.Name,
		Content:    data.Content,
		Hash:       data.Hash,
		MtimeMs:    data.MtimeMs,
		Size:       data.Size,
		LineEnding: data.LineEnding,
		BOM:        data.BOM,
	}, nil
}

func readFileData(path string) (*fileData, error) {
	path = strings.TrimSpace(path)
	if path == "" {
		return nil, errors.New("文件路径不能为空")
	}
	if !isMarkdownPath(path) {
		return nil, errors.New("只支持打开 .md / .markdown 文件")
	}

	realPath, err := filepath.EvalSymlinks(path)
	if err != nil {
		return nil, fmt.Errorf("读取文件: %w", err)
	}
	info, err := os.Stat(realPath)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("只能打开普通文件")
	}
	if info.Size() > maxFileSize {
		return nil, fmt.Errorf("文件超过 %d MB，请用外部编辑器打开", maxFileSize>>20)
	}

	raw, err := os.ReadFile(realPath)
	if err != nil {
		return nil, fmt.Errorf("读取文件: %w", err)
	}
	if len(raw) > maxFileSize {
		return nil, fmt.Errorf("文件超过 %d MB，请用外部编辑器打开", maxFileSize>>20)
	}

	bom := bytes.HasPrefix(raw, []byte{0xEF, 0xBB, 0xBF})
	if bom {
		raw = raw[3:]
	}
	if !utf8.Valid(raw) {
		return nil, errors.New("文件不是 UTF-8 编码，暂不支持打开")
	}

	sum := sha256.Sum256(raw)
	return &fileData{
		Path:       realPath,
		Name:       filepath.Base(realPath),
		Content:    string(raw),
		Hash:       hex.EncodeToString(sum[:]),
		MtimeMs:    info.ModTime().UnixMilli(),
		Size:       int64(len(raw)),
		LineEnding: detectLineEnding(raw),
		BOM:        bom,
		Mode:       info.Mode(),
	}, nil
}

func isMarkdownPath(path string) bool {
	ext := strings.ToLower(filepath.Ext(path))
	return ext == ".md" || ext == ".markdown"
}

func detectLineEnding(data []byte) string {
	index := bytes.IndexByte(data, '\n')
	if index > 0 && data[index-1] == '\r' {
		return "crlf"
	}
	return "lf"
}

func encodeContent(content, lineEnding string, bom bool) []byte {
	normalized := strings.ReplaceAll(content, "\r\n", "\n")
	normalized = strings.ReplaceAll(normalized, "\r", "\n")
	if lineEnding == "crlf" {
		normalized = strings.ReplaceAll(normalized, "\n", "\r\n")
	}
	data := []byte(normalized)
	if bom {
		data = append([]byte{0xEF, 0xBB, 0xBF}, data...)
	}
	return data
}

func writeAtomic(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".simple-notes-*.tmp")
	if err != nil {
		return fmt.Errorf("创建临时文件: %w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return fmt.Errorf("写入临时文件: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return fmt.Errorf("刷新临时文件: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("关闭临时文件: %w", err)
	}
	if mode != 0 {
		if err := os.Chmod(tmpName, mode.Perm()); err != nil {
			return fmt.Errorf("设置文件权限: %w", err)
		}
	}
	if err := os.Rename(tmpName, path); err != nil {
		return fmt.Errorf("替换目标文件: %w", err)
	}
	return nil
}

// defaultMarkdownName 生成安全默认文件名：去非法字符、限制长度、补 .md。
func defaultMarkdownName(title string) string {
	title = strings.TrimSpace(title)
	var builder strings.Builder
	for _, r := range title {
		switch {
		case r < 0x20 || r == 0x7f:
			continue
		case strings.ContainsRune(`\/:*?"<>|`, r):
			builder.WriteRune('-')
		default:
			builder.WriteRune(r)
		}
	}
	name := strings.Trim(builder.String(), " .")
	if name == "" {
		name = "未命名"
	}
	runes := []rune(name)
	if len(runes) > 100 {
		name = string(runes[:100])
	}
	if !strings.HasSuffix(strings.ToLower(name), ".md") &&
		!strings.HasSuffix(strings.ToLower(name), ".markdown") {
		name += ".md"
	}
	return name
}
