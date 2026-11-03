package media

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"cc.naihao/simplenotes/desktop/internal/store"
)

// LocalRefPrefix 是本地笔记图片的逻辑引用前缀。
const LocalRefPrefix = "local-media/"

var imageRefPattern = regexp.MustCompile(`!\[([^\]]*)\]\(\s*([^)\s]+)([^)]*)\)`)

// RewriteFileRefsToLocal 把临时文件里的相对图片引用复制到本地媒体库，改写为 local-media/<id>。
//
//wails:ignore
func (s *Service) RewriteFileRefsToLocal(content, sourcePath string) (string, error) {
	if !strings.Contains(content, "![") {
		return content, nil
	}
	return s.rewriteImageRefs(content, func(ref string) (string, bool, error) {
		if isExternalRef(ref) || strings.HasPrefix(ref, LocalRefPrefix) {
			return "", false, nil
		}
		path, err := resolveRefPath(ref, sourcePath)
		if err != nil {
			return "", false, err
		}
		item, err := s.saveLocalPath(path, filepath.Base(path))
		if err != nil {
			return "", false, err
		}
		return LocalRefPrefix + item.ID, true, nil
	})
}

// RewriteFileRefsToServer 把临时文件里的相对图片引用上传到服务端，改写为 /api/media/<id>。
//
//wails:ignore
func (s *Service) RewriteFileRefsToServer(content, sourcePath string) (string, error) {
	if !strings.Contains(content, "![") {
		return content, nil
	}
	return s.rewriteImageRefs(content, func(ref string) (string, bool, error) {
		if isExternalRef(ref) || strings.HasPrefix(ref, "/api/media/") {
			return "", false, nil
		}
		if strings.HasPrefix(ref, LocalRefPrefix) {
			item, found, err := s.localAttachment(strings.TrimPrefix(ref, LocalRefPrefix))
			if err != nil {
				return "", false, err
			}
			if !found {
				return "", false, errors.New("本地图片不存在：" + ref)
			}
			attachment, err := s.uploadServerPath(s.localAttachmentPath(item), item.OriginalName)
			if err != nil {
				return "", false, err
			}
			return "/api/media/" + attachment.ID, true, nil
		}
		path, err := resolveRefPath(ref, sourcePath)
		if err != nil {
			return "", false, err
		}
		attachment, err := s.uploadServerPath(path, filepath.Base(path))
		if err != nil {
			return "", false, err
		}
		return "/api/media/" + attachment.ID, true, nil
	})
}

// RewriteLocalRefsToServer 只处理本地笔记的 local-media 引用。
//
//wails:ignore
func (s *Service) RewriteLocalRefsToServer(content string) (string, error) {
	return s.RewriteFileRefsToServer(content, "")
}

// RewriteLocalRefsToExport 把本地笔记图片复制到导出文件旁的 assets 目录，改写为相对路径。
//
//wails:ignore
func (s *Service) RewriteLocalRefsToExport(content, targetPath string) (string, error) {
	if !strings.Contains(content, "!") {
		return content, nil
	}
	assetsDir := assetsDirFor(targetPath)
	copied := make(map[string]string, 4)
	return s.rewriteImageRefs(content, func(ref string) (string, bool, error) {
		if !strings.HasPrefix(ref, LocalRefPrefix) {
			return "", false, nil
		}
		id := strings.TrimPrefix(ref, LocalRefPrefix)
		if existing, ok := copied[id]; ok {
			return existing, true, nil
		}
		item, found, err := s.localAttachment(id)
		if err != nil {
			return "", false, err
		}
		if !found {
			return "", false, errors.New("本地图片不存在：" + ref)
		}
		filename, err := copyAsset(s.localAttachmentPath(item), assetsDir, item.OriginalName, filepath.Ext(item.StorageKey))
		if err != nil {
			return "", false, err
		}
		rel := "./" + pathJoin(filepath.Base(assetsDir), filename)
		copied[id] = rel
		return rel, true, nil
	})
}

// RewriteFileRefsForSaveAs 在另存为到其他目录时复制旧 assets 目录中的图片并改写相对路径。
//
//wails:ignore
func (s *Service) RewriteFileRefsForSaveAs(content, sourcePath, targetPath string) (string, error) {
	if sourcePath == "" || targetPath == "" || !strings.Contains(content, "!") {
		return content, nil
	}
	sourceDir := assetsDirFor(sourcePath)
	targetDir := assetsDirFor(targetPath)
	if filepath.Clean(sourceDir) == filepath.Clean(targetDir) {
		return content, nil
	}
	copied := make(map[string]string, 4)
	return s.rewriteImageRefs(content, func(ref string) (string, bool, error) {
		if isExternalRef(ref) || strings.HasPrefix(ref, "/api/") || strings.HasPrefix(ref, LocalRefPrefix) {
			return "", false, nil
		}
		source, err := resolveRefPath(ref, sourcePath)
		if err != nil {
			// 外部相对引用（例如 ../pics/a.png）不强制复制；另存为后仍按原相对路径解析。
			return "", false, nil
		}
		within, err := filepath.Rel(sourceDir, source)
		if err != nil || within == ".." || strings.HasPrefix(within, ".."+string(os.PathSeparator)) {
			return "", false, nil
		}
		if existing, ok := copied[source]; ok {
			return existing, true, nil
		}
		filename, err := copyAsset(source, targetDir, filepath.Base(source), filepath.Ext(source))
		if err != nil {
			return "", false, err
		}
		rel := "./" + pathJoin(filepath.Base(targetDir), filename)
		copied[source] = rel
		return rel, true, nil
	})
}

// HasLocalRefs 报告正文是否含本地媒体引用。
func HasLocalRefs(content string) bool {
	return strings.Contains(content, "!") && strings.Contains(content, LocalRefPrefix)
}

// CopyLocalRefs 只复制本地引用并返回新正文；用于后续清理引用扫描。
//
//wails:ignore
func (s *Service) CopyLocalRefs(content, targetPath string) (string, error) {
	return s.RewriteLocalRefsToExport(content, targetPath)
}

// Cleanup 删除没有任何本地笔记引用的本地图片；在本地笔记保存 / 删除后调用。
//
//wails:ignore
func (s *Service) Cleanup() error {
	contents, err := s.db.ListLocalNoteContents()
	if err != nil {
		return err
	}
	referenced := make(map[string]struct{}, 16)
	for _, content := range contents {
		for _, match := range localRefIDPattern.FindAllStringSubmatch(content, -1) {
			if len(match) > 1 {
				referenced[match[1]] = struct{}{}
			}
		}
	}
	ids, err := s.db.ListLocalAttachmentIDs()
	if err != nil {
		return err
	}
	// 宽限期：刚插入、尚未自动保存的图片可能还不在 note_local.content 里。
	cutoff := time.Now().Add(-10 * time.Minute).UnixMilli()
	for _, id := range ids {
		if _, ok := referenced[id]; ok {
			continue
		}
		item, found, err := s.db.GetLocalAttachment(id)
		if err != nil || !found || item.CreateTime > cutoff {
			continue
		}
		_ = os.Remove(s.localAttachmentPath(item))
		_ = s.db.DeleteLocalAttachment(id)
	}
	return nil
}

var localRefIDPattern = regexp.MustCompile(`local-media/([0-9a-fA-F-]{8,})`)

func (s *Service) rewriteImageRefs(content string, resolve func(string) (string, bool, error)) (string, error) {
	matches := imageRefPattern.FindAllStringSubmatchIndex(content, -1)
	if len(matches) == 0 {
		return content, nil
	}
	var builder strings.Builder
	last := 0
	for _, match := range matches {
		builder.WriteString(content[last:match[0]])
		alt := content[match[2]:match[3]]
		ref := content[match[4]:match[5]]
		rest := content[match[6]:match[7]]
		if replaced, ok, err := resolve(ref); err != nil {
			return "", err
		} else if ok {
			builder.WriteString("![")
			builder.WriteString(alt)
			builder.WriteString("](")
			builder.WriteString(replaced)
			builder.WriteString(rest)
			builder.WriteString(")")
		} else {
			builder.WriteString(content[match[0]:match[1]])
		}
		last = match[1]
	}
	builder.WriteString(content[last:])
	return builder.String(), nil
}

func (s *Service) localAttachment(id string) (store.LocalAttachment, bool, error) {
	if id == "" || strings.ContainsAny(id, `/\`) {
		return store.LocalAttachment{}, false, nil
	}
	return s.db.GetLocalAttachment(id)
}

func (s *Service) localAttachmentPath(item store.LocalAttachment) string {
	return filepath.Join(s.root, item.StorageKey)
}

func isExternalRef(ref string) bool {
	lower := strings.ToLower(ref)
	return strings.HasPrefix(lower, "http://") ||
		strings.HasPrefix(lower, "https://") ||
		strings.HasPrefix(lower, "data:") ||
		strings.HasPrefix(lower, "file:") ||
		strings.HasPrefix(ref, "/api/") ||
		strings.HasPrefix(ref, "/oss/") ||
		strings.HasPrefix(ref, "#")
}

func resolveRefPath(ref, sourcePath string) (string, error) {
	value := strings.TrimSpace(ref)
	if strings.HasPrefix(value, "<") && strings.HasSuffix(value, ">") {
		value = strings.TrimSuffix(strings.TrimPrefix(value, "<"), ">")
	}
	if decoded, err := url.PathUnescape(value); err == nil {
		value = decoded
	}
	if strings.HasPrefix(value, "/api/media/") || strings.HasPrefix(value, "/oss/") {
		return "", errors.New("不是本地图片：" + ref)
	}
	if filepath.IsAbs(value) {
		return checkImageFile(value)
	}
	if sourcePath == "" {
		return "", errors.New("无法解析相对图片路径：" + ref)
	}
	return checkImageFile(filepath.Join(filepath.Dir(sourcePath), filepath.FromSlash(value)))
}

func checkImageFile(path string) (string, error) {
	realPath, err := filepath.EvalSymlinks(path)
	if err != nil {
		return "", fmt.Errorf("图片不存在：%s", filepath.Base(path))
	}
	info, err := os.Stat(realPath)
	if err != nil {
		return "", fmt.Errorf("图片不存在：%s", filepath.Base(path))
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("不是普通文件：%s", filepath.Base(path))
	}
	file, err := os.Open(realPath)
	if err != nil {
		return "", err
	}
	defer file.Close()
	header, err := readHeader(file)
	if err != nil {
		return "", err
	}
	if _, err := detectImage(header); err != nil {
		return "", fmt.Errorf("%s：%w", filepath.Base(path), err)
	}
	return realPath, nil
}

func assetsDirFor(markdownPath string) string {
	base := strings.TrimSuffix(filepath.Base(markdownPath), filepath.Ext(markdownPath))
	if base == "" {
		base = "assets"
	}
	return filepath.Join(filepath.Dir(markdownPath), base+".assets")
}

func copyAsset(source, targetDir, originalName, ext string) (string, error) {
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		return "", fmt.Errorf("创建图片目录: %w", err)
	}
	stem := strings.TrimSuffix(filepath.Base(originalName), filepath.Ext(originalName))
	ext = strings.TrimPrefix(strings.ToLower(ext), ".")
	if ext == "" {
		ext = "png"
	}
	filename := uniqueAssetName(targetDir, safeStem(stem), ext)
	target := filepath.Join(targetDir, filename)
	src, err := os.Open(source)
	if err != nil {
		return "", fmt.Errorf("读取图片: %w", err)
	}
	defer src.Close()
	if _, err := writeStream(target, src); err != nil {
		return "", err
	}
	return filename, nil
}
