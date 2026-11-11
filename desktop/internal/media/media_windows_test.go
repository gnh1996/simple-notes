//go:build windows

package media

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"unsafe"

	"cc.naihao/simplenotes/desktop/internal/store"
)

var procGetShortPathName = syscall.NewLazyDLL("kernel32.dll").NewProc("GetShortPathNameW")

// shortPathName 返回 path 的 8.3 短路径；当前卷未启用短文件名时返回原路径。
func shortPathName(t *testing.T, path string) string {
	t.Helper()
	ptr, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		t.Fatalf("utf16: %v", err)
	}
	buf := make([]uint16, 512)
	n, _, callErr := procGetShortPathName.Call(
		uintptr(unsafe.Pointer(ptr)),
		uintptr(unsafe.Pointer(&buf[0])),
		uintptr(len(buf)),
	)
	if n == 0 {
		t.Skipf("GetShortPathName 调用失败，跳过: %v", callErr)
	}
	return syscall.UTF16ToString(buf[:n])
}

// TestLocalMediaWithShortRootPath 复现 Windows CI 的 8.3 短路径（C:\Users\RUNNER~1\...）场景：
// EvalSymlinks 会把短路径还原成长路径，safeJoin 若拿未解析的 root 比对，就会把合法文件误判为越界并返回 404。
func TestLocalMediaWithShortRootPath(t *testing.T) {
	longDir := filepath.Join(t.TempDir(), "verylongdirectoryname")
	if err := os.MkdirAll(longDir, 0o755); err != nil {
		t.Fatal(err)
	}
	shortDir := shortPathName(t, longDir)
	if filepath.Clean(shortDir) == filepath.Clean(longDir) {
		t.Skip("当前卷未启用 8.3 短文件名，跳过")
	}

	db, err := store.OpenAt(filepath.Join(shortDir, "desktop.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	service := New(db, nil)
	item, err := service.saveLocalStream(bytes.NewReader(testPNG), "shot.png", imageType{MimeType: "image/png", Ext: "png"})
	if err != nil {
		t.Fatalf("save local: %v", err)
	}
	req := httptest.NewRequest(http.MethodGet, "/local-media/"+item.ID, nil)
	recorder := httptest.NewRecorder()
	service.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d", recorder.Code)
	}
	if !bytes.Equal(recorder.Body.Bytes(), testPNG) {
		t.Fatal("body mismatch")
	}
}
