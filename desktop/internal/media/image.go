package media

import (
	"errors"
	"net/http"
	"strings"
)

const (
	// MaxImageSize 与后端附件上限保持一致（docs/attachment-storage.md）。
	MaxImageSize = 20 << 20
)

var errNotImage = errors.New("仅支持 PNG / JPEG / GIF / WebP / AVIF 图片")

type imageType struct {
	MimeType string
	Ext      string
}

// detectImage 用文件头判断图片类型；不信任扩展名 / Content-Type。
func detectImage(header []byte) (imageType, error) {
	switch {
	case len(header) >= 8 && string(header[:8]) == "\x89PNG\r\n\x1a\n":
		return imageType{MimeType: "image/png", Ext: "png"}, nil
	case len(header) >= 3 && header[0] == 0xFF && header[1] == 0xD8 && header[2] == 0xFF:
		return imageType{MimeType: "image/jpeg", Ext: "jpg"}, nil
	case len(header) >= 6 && (string(header[:6]) == "GIF87a" || string(header[:6]) == "GIF89a"):
		return imageType{MimeType: "image/gif", Ext: "gif"}, nil
	case len(header) >= 12 && string(header[:4]) == "RIFF" && string(header[8:12]) == "WEBP":
		return imageType{MimeType: "image/webp", Ext: "webp"}, nil
	case len(header) >= 12 && string(header[4:8]) == "ftyp":
		brand := string(header[8:12])
		if brand == "avif" || brand == "avis" {
			return imageType{MimeType: "image/avif", Ext: "avif"}, nil
		}
	}
	return imageType{}, errNotImage
}

func isImageMime(mimeType string) bool {
	switch strings.ToLower(strings.TrimSpace(mimeType)) {
	case "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif":
		return true
	default:
		return false
	}
}

func sniffImage(header []byte) string {
	if detected, err := detectImage(header); err == nil {
		return detected.MimeType
	}
	// 反弹回退：只用于拒绝时给出更明确的信息，不作为安全依据。
	return http.DetectContentType(header)
}
