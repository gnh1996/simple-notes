// Package media 负责桌面端图片的本地存储、文件相对路径、服务端上传与同源读取。
//
// 设计见 docs/desktop-media.md：
//   - 文件模式：图片放在 Markdown 同目录的 <stem>.assets/，正文写相对路径；
//   - 本地笔记：图片放在 desktop.db 同级的 media/local/，正文写 local-media/<id>；
//   - 服务端笔记：上传到 /api/desktop/media，正文写 /api/media/<id>；
//   - WebView 只访问 Wails 同源路由，不直接访问后端。
package media

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/store"
)

const maxAssetName = 120

// Service 是桌面端媒体服务；既作为 Wails binding，也实现 /__media 的 HTTP handler。
type Service struct {
	db   *store.Store
	api  *api.Service
	root string

	mu        sync.RWMutex
	fileRoots map[string]fileRoot

	proxyClient *http.Client
}

type fileRoot struct {
	Dir  string
	Stem string
}

// New 创建媒体服务。db 用于本地附件元数据，api 用于服务端上传与 /api/media 代理。
func New(db *store.Store, apiService *api.Service) *Service {
	return &Service{
		db:        db,
		api:       apiService,
		root:      filepath.Join(filepath.Dir(db.Path()), "media", "local"),
		fileRoots: make(map[string]fileRoot),
		proxyClient: &http.Client{
			Timeout: 60 * time.Second,
		},
	}
}

// RegisterFileTab 为一个临时文件标签登记根目录，返回用于 /__media/file/<token>/ 的不透明 token。
func (s *Service) RegisterFileTab(path string) (string, error) {
	path = strings.TrimSpace(path)
	if path == "" {
		return "", errors.New("文件路径不能为空")
	}
	realPath, err := filepath.EvalSymlinks(path)
	if err != nil {
		return "", fmt.Errorf("登记文件目录: %w", err)
	}
	info, err := os.Stat(realPath)
	if err != nil {
		return "", err
	}
	if !info.Mode().IsRegular() {
		return "", errors.New("只能登记普通文件")
	}
	token, err := randomToken()
	if err != nil {
		return "", err
	}
	stem := strings.TrimSuffix(filepath.Base(realPath), filepath.Ext(realPath))
	if stem == "" {
		stem = "assets"
	}
	s.mu.Lock()
	s.fileRoots[token] = fileRoot{Dir: filepath.Dir(realPath), Stem: stem}
	s.mu.Unlock()
	return token, nil
}

// ReleaseFileTab 释放文件标签 token。
func (s *Service) ReleaseFileTab(token string) {
	if token == "" {
		return
	}
	s.mu.Lock()
	delete(s.fileRoots, token)
	s.mu.Unlock()
}

func (s *Service) rootForToken(token string) (fileRoot, bool) {
	s.mu.RLock()
	root, ok := s.fileRoots[token]
	s.mu.RUnlock()
	return root, ok
}

// ServeHTTP 处理 Wails 内置资产服务器上的 /__media 路由。
//
//wails:ignore
func (s *Service) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	switch {
	case path == "/import" || path == "/import/":
		s.handleImport(w, r)
	case strings.HasPrefix(path, "/file/"):
		s.handleFile(w, r)
	case strings.HasPrefix(path, "/local-media/"):
		s.handleLocalMedia(w, r)
	default:
		http.NotFound(w, r)
	}
}

// HandleProxy 处理 WebView 对 /api/media/** 与 /oss/** 的只读请求；返回 true 表示已处理。
//
// 这是“WebView 不直连后端”的显式流式例外：请求先到 Wails 资产服务，再由 Go 转给后端。
//
//wails:ignore
func (s *Service) HandleProxy(w http.ResponseWriter, r *http.Request) bool {
	if !strings.HasPrefix(r.URL.Path, "/api/media/") && !strings.HasPrefix(r.URL.Path, "/oss/") {
		return false
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return true
	}
	if s.api == nil {
		http.Error(w, "服务端未配置", http.StatusServiceUnavailable)
		return true
	}
	base, err := url.Parse(s.api.BaseURL())
	if err != nil || base.Scheme == "" || base.Host == "" {
		http.Error(w, "服务端地址无效", http.StatusServiceUnavailable)
		return true
	}
	target := *base
	target.Path = r.URL.Path
	target.RawQuery = r.URL.RawQuery

	req, err := http.NewRequestWithContext(r.Context(), r.Method, target.String(), nil)
	if err != nil {
		http.Error(w, "构造媒体请求失败", http.StatusBadGateway)
		return true
	}
	req.Header.Set("Accept", "image/*,*/*;q=0.8")
	for _, name := range []string{"Range", "If-None-Match", "If-Modified-Since"} {
		if value := r.Header.Get(name); value != "" {
			req.Header.Set(name, value)
		}
	}
	resp, err := s.proxyClient.Do(req)
	if err != nil {
		http.Error(w, "媒体暂不可用", http.StatusBadGateway)
		return true
	}
	defer resp.Body.Close()

	copyHeader := func(name string) {
		if value := resp.Header.Get(name); value != "" {
			w.Header().Set(name, value)
		}
	}
	copyHeader("Content-Type")
	copyHeader("Content-Length")
	copyHeader("Content-Disposition")
	copyHeader("Content-Range")
	copyHeader("Accept-Ranges")
	copyHeader("Cache-Control")
	copyHeader("ETag")
	copyHeader("Last-Modified")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(resp.StatusCode)
	if r.Method != http.MethodHead {
		_, _ = io.Copy(w, resp.Body)
	}
	return true
}

// ImportResult 是一次图片导入的返回。
type ImportResult struct {
	Ref string `json:"ref"`
	URL string `json:"url"`
	Alt string `json:"alt"`
}

func (s *Service) handleImport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	target := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("target")))
	token := strings.TrimSpace(r.URL.Query().Get("token"))
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if name == "" {
		name = "image"
	}

	reader := bufio.NewReader(http.MaxBytesReader(w, r.Body, MaxImageSize+1))
	header, err := reader.Peek(512)
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, bufio.ErrBufferFull) {
		writeError(w, http.StatusBadRequest, "读取图片失败")
		return
	}
	detected, err := detectImage(header)
	if err != nil {
		writeError(w, http.StatusBadRequest, errNotImage.Error())
		return
	}

	var result ImportResult
	switch target {
	case "local":
		item, err := s.saveLocalStream(reader, name, detected)
		if err != nil {
			writeError(w, importStatus(err), err.Error())
			return
		}
		result = ImportResult{
			Ref: "local-media/" + item.ID,
			URL: "/__media/local-media/" + item.ID,
			Alt: altText(name),
		}
	case "file":
		root, ok := s.rootForToken(token)
		if !ok {
			writeError(w, http.StatusConflict, "文件标签未注册，请先保存文件")
			return
		}
		ref, fileURL, err := s.saveFileStream(root, token, reader, name, detected)
		if err != nil {
			writeError(w, importStatus(err), err.Error())
			return
		}
		result = ImportResult{Ref: ref, URL: fileURL, Alt: altText(name)}
	case "server":
		attachment, err := s.uploadServerStream(reader, name, detected)
		if err != nil {
			writeError(w, importStatus(err), err.Error())
			return
		}
		result = ImportResult{
			Ref: "/api/media/" + attachment.ID,
			URL: "/api/media/" + attachment.ID,
			Alt: altText(name),
		}
	default:
		writeError(w, http.StatusBadRequest, "未知的图片目标")
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Service) handleFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/file/")
	token, rel, ok := strings.Cut(rest, "/")
	if !ok || token == "" || rel == "" {
		http.NotFound(w, r)
		return
	}
	root, ok := s.rootForToken(token)
	if !ok {
		http.NotFound(w, r)
		return
	}
	target, err := safeJoin(root.Dir, rel)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	serveImage(w, r, target, "no-store")
}

func (s *Service) handleLocalMedia(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/local-media/")
	if id == "" || strings.ContainsAny(id, `/\`) {
		http.NotFound(w, r)
		return
	}
	item, found, err := s.db.GetLocalAttachment(id)
	if err != nil || !found {
		http.NotFound(w, r)
		return
	}
	target, err := safeJoin(s.root, item.StorageKey)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	serveImage(w, r, target, "public, max-age=31536000, immutable")
}

func (s *Service) saveLocalStream(reader io.Reader, name string, detected imageType) (store.LocalAttachment, error) {
	if err := os.MkdirAll(s.root, 0o700); err != nil {
		return store.LocalAttachment{}, fmt.Errorf("创建本地图片目录: %w", err)
	}
	id := store.NewUUID()
	storageKey := id + "." + detected.Ext
	target := filepath.Join(s.root, storageKey)
	size, err := writeStream(target, reader)
	if err != nil {
		return store.LocalAttachment{}, err
	}
	item := store.LocalAttachment{
		ID:           id,
		ScopeID:      "local",
		OriginalName: safeOriginalName(name, detected.Ext),
		MimeType:     detected.MimeType,
		SizeBytes:    size,
		StorageKey:   storageKey,
		CreateTime:   time.Now().UnixMilli(),
	}
	if err := s.db.InsertLocalAttachment(item); err != nil {
		_ = os.Remove(target)
		return store.LocalAttachment{}, fmt.Errorf("保存本地图片记录: %w", err)
	}
	return item, nil
}

func (s *Service) saveLocalPath(path, name string) (store.LocalAttachment, error) {
	file, err := os.Open(path)
	if err != nil {
		return store.LocalAttachment{}, fmt.Errorf("读取图片: %w", err)
	}
	defer file.Close()
	header, err := readHeader(file)
	if err != nil {
		return store.LocalAttachment{}, err
	}
	detected, err := detectImage(header)
	if err != nil {
		return store.LocalAttachment{}, err
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return store.LocalAttachment{}, err
	}
	if name == "" {
		name = filepath.Base(path)
	}
	return s.saveLocalStream(file, name, detected)
}

func (s *Service) saveFileStream(root fileRoot, token string, reader io.Reader, name string, detected imageType) (string, string, error) {
	dir := filepath.Join(root.Dir, root.Stem+".assets")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", "", fmt.Errorf("创建图片目录: %w", err)
	}
	filename := uniqueAssetName(dir, safeStem(name), detected.Ext)
	target := filepath.Join(dir, filename)
	if _, err := writeStream(target, reader); err != nil {
		return "", "", err
	}
	rel := pathJoin(root.Stem+".assets", filename)
	return "./" + rel, "/__media/file/" + url.PathEscape(token) + "/" + rel, nil
}

func (s *Service) saveFileFromPath(root fileRoot, token, sourcePath, name string) (string, string, error) {
	file, err := os.Open(sourcePath)
	if err != nil {
		return "", "", fmt.Errorf("读取图片: %w", err)
	}
	defer file.Close()
	header, err := readHeader(file)
	if err != nil {
		return "", "", err
	}
	detected, err := detectImage(header)
	if err != nil {
		return "", "", err
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return "", "", err
	}
	if name == "" {
		name = filepath.Base(sourcePath)
	}
	return s.saveFileStream(root, token, file, name, detected)
}

// serverAttachment 是后端 AttachmentResponse 的最小子集。
type serverAttachment struct {
	ID           string `json:"id"`
	URL          string `json:"url"`
	OriginalName string `json:"originalName"`
	MimeType     string `json:"mimeType"`
	SizeBytes    int64  `json:"sizeBytes"`
}

func (s *Service) uploadServerStream(reader io.Reader, name string, detected imageType) (serverAttachment, error) {
	if s.api == nil {
		return serverAttachment{}, errors.New("未配置服务端")
	}
	token, ok, err := s.db.Token()
	if err != nil {
		return serverAttachment{}, fmt.Errorf("读取会话: %w", err)
	}
	if !ok || token == "" {
		return serverAttachment{}, errUnauthorized
	}

	bodyFile, err := os.CreateTemp("", "simple-notes-upload-*.multipart")
	if err != nil {
		return serverAttachment{}, fmt.Errorf("创建上传临时文件: %w", err)
	}
	bodyPath := bodyFile.Name()
	defer os.Remove(bodyPath)
	defer bodyFile.Close()

	writer := multipart.NewWriter(bodyFile)
	part, err := writer.CreateFormFile("file", safeOriginalName(name, detected.Ext))
	if err != nil {
		return serverAttachment{}, err
	}
	if _, err := copyLimited(part, reader); err != nil {
		return serverAttachment{}, err
	}
	if err := writer.Close(); err != nil {
		return serverAttachment{}, err
	}
	if err := bodyFile.Close(); err != nil {
		return serverAttachment{}, err
	}

	info, err := os.Stat(bodyPath)
	if err != nil {
		return serverAttachment{}, err
	}
	body, err := os.Open(bodyPath)
	if err != nil {
		return serverAttachment{}, err
	}
	defer body.Close()

	req, err := http.NewRequest(http.MethodPost, s.api.BaseURL()+"/desktop/media", body)
	if err != nil {
		return serverAttachment{}, err
	}
	req.ContentLength = info.Size()
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)

	resp, err := s.proxyClient.Do(req)
	if err != nil {
		return serverAttachment{}, fmt.Errorf("上传图片失败: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return serverAttachment{}, fmt.Errorf("%s", api.ErrorMessage(resp.StatusCode, string(raw)))
	}
	var attachment serverAttachment
	if err := json.Unmarshal(raw, &attachment); err != nil {
		return serverAttachment{}, fmt.Errorf("解析上传响应: %w", err)
	}
	if attachment.ID == "" {
		return serverAttachment{}, errors.New("服务端未返回附件 id")
	}
	return attachment, nil
}

func (s *Service) uploadServerPath(path, name string) (serverAttachment, error) {
	file, err := os.Open(path)
	if err != nil {
		return serverAttachment{}, fmt.Errorf("读取图片: %w", err)
	}
	defer file.Close()
	header, err := readHeader(file)
	if err != nil {
		return serverAttachment{}, err
	}
	detected, err := detectImage(header)
	if err != nil {
		return serverAttachment{}, err
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return serverAttachment{}, err
	}
	if name == "" {
		name = filepath.Base(path)
	}
	return s.uploadServerStream(file, name, detected)
}

var errUnauthorized = errors.New("请先登录后再上传服务端图片")

func writeStream(target string, reader io.Reader) (int64, error) {
	dir := filepath.Dir(target)
	tmp, err := os.CreateTemp(dir, ".simple-notes-image-*.tmp")
	if err != nil {
		return 0, fmt.Errorf("创建图片临时文件: %w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	size, err := copyLimited(tmp, reader)
	if err != nil {
		tmp.Close()
		return 0, err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return 0, err
	}
	if err := tmp.Close(); err != nil {
		return 0, err
	}
	if err := os.Chmod(tmpName, 0o644); err != nil {
		return 0, err
	}
	if err := os.Rename(tmpName, target); err != nil {
		return 0, fmt.Errorf("写入图片: %w", err)
	}
	return size, nil
}

func copyLimited(dst io.Writer, src io.Reader) (int64, error) {
	n, err := io.Copy(dst, io.LimitReader(src, MaxImageSize+1))
	if err != nil {
		return n, fmt.Errorf("写入图片: %w", err)
	}
	if n > MaxImageSize {
		return n, fmt.Errorf("图片不能超过 %d MB", MaxImageSize>>20)
	}
	return n, nil
}

func readHeader(file *os.File) ([]byte, error) {
	header := make([]byte, 512)
	n, err := io.ReadFull(file, header)
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
		return nil, err
	}
	return header[:n], nil
}

func serveImage(w http.ResponseWriter, r *http.Request, path, cacheControl string) {
	file, err := os.Open(path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		http.NotFound(w, r)
		return
	}
	if info.Size() > MaxImageSize {
		http.Error(w, "图片过大", http.StatusRequestEntityTooLarge)
		return
	}
	header, err := readHeader(file)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	detected, err := detectImage(header)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", detected.MimeType)
	w.Header().Set("Content-Disposition", "inline")
	w.Header().Set("Cache-Control", cacheControl)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, filepath.Base(path), info.ModTime(), file)
}

func safeJoin(root, rel string) (string, error) {
	if strings.ContainsRune(rel, 0) || strings.Contains(rel, `\`) {
		return "", errors.New("非法路径")
	}
	clean := filepath.Clean(filepath.FromSlash(rel))
	if clean == "." || filepath.IsAbs(clean) || clean == ".." ||
		strings.HasPrefix(clean, ".."+string(os.PathSeparator)) {
		return "", errors.New("非法路径")
	}
	target, err := filepath.EvalSymlinks(filepath.Join(root, clean))
	if err != nil {
		return "", err
	}
	within, err := filepath.Rel(root, target)
	if err != nil || within == ".." || strings.HasPrefix(within, ".."+string(os.PathSeparator)) {
		return "", errors.New("路径越界")
	}
	return target, nil
}

func uniqueAssetName(dir, stem, ext string) string {
	stem = strings.TrimSpace(stem)
	if stem == "" {
		stem = "image"
	}
	runes := []rune(stem)
	if len(runes) > maxAssetName {
		stem = string(runes[:maxAssetName])
	}
	base := stem
	for i := 0; i < 20; i++ {
		name := fmt.Sprintf("%s-%s.%s", base, randomSuffix(), ext)
		if _, err := os.Stat(filepath.Join(dir, name)); errors.Is(err, os.ErrNotExist) {
			return name
		}
	}
	return fmt.Sprintf("%s-%d.%s", base, time.Now().UnixNano(), ext)
}

func safeStem(name string) string {
	name = strings.TrimSpace(name)
	name = strings.TrimSuffix(name, filepath.Ext(name))
	var builder strings.Builder
	lastDash := false
	for _, r := range name {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9',
			r == '.', r == '_', r == '-':
			builder.WriteRune(r)
			lastDash = false
		case r > 127:
			builder.WriteRune(r)
			lastDash = false
		default:
			if !lastDash {
				builder.WriteRune('-')
				lastDash = true
			}
		}
	}
	stem := strings.Trim(builder.String(), " .-_")
	if stem == "" {
		stem = "image"
	}
	return stem
}

func safeOriginalName(name, ext string) string {
	base := filepath.Base(strings.TrimSpace(name))
	base = strings.TrimSuffix(base, filepath.Ext(base))
	base = safeStem(base)
	if ext != "" && !strings.HasSuffix(strings.ToLower(base), "."+ext) {
		base += "." + ext
	}
	return base
}

func altText(name string) string {
	return strings.TrimSuffix(filepath.Base(strings.TrimSpace(name)), filepath.Ext(name))
}

func pathJoin(elem ...string) string {
	escaped := make([]string, 0, len(elem))
	for _, item := range elem {
		escaped = append(escaped, url.PathEscape(item))
	}
	return strings.Join(escaped, "/")
}

func randomSuffix() string {
	token, err := randomToken()
	if err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return token[:8]
}

func randomToken() (string, error) {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"message": message})
}

func importStatus(err error) int {
	switch {
	case errors.Is(err, errUnauthorized):
		return http.StatusUnauthorized
	case strings.Contains(err.Error(), "不能超过"):
		return http.StatusRequestEntityTooLarge
	case strings.Contains(err.Error(), "未注册"):
		return http.StatusConflict
	case strings.Contains(err.Error(), "未配置"), strings.Contains(err.Error(), "失败"):
		return http.StatusBadGateway
	default:
		return http.StatusBadRequest
	}
}
