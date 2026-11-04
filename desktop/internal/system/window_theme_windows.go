//go:build windows

package system

import (
	"os"
	"syscall"

	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/w32"
)

// applyWindowTheme 让原生窗口标题栏 / 边框跟随应用主题。
// Wails 只在启动时按系统主题设置一次；用户手动切浅色 / 深色后需要重新下发。
func applyWindowTheme(dark bool) {
	application.InvokeAsync(func() {
		if hwnd := mainWindowHandle(); hwnd != 0 {
			w32.SetTheme(hwnd, dark)
		}
	})
}

// mainWindowHandle 找到当前进程的可见顶层窗口（应用只有一个主窗口）。
// 找到后缓存：SetWindowTheme 只在主线程串行调用，不需要额外加锁。
var cachedWindowHandle w32.HWND

func mainWindowHandle() w32.HWND {
	if cachedWindowHandle != 0 {
		return cachedWindowHandle
	}
	var found w32.HWND
	pid := uint32(os.Getpid())
	callback := syscall.NewCallback(func(hwnd w32.HWND, _ uintptr) uintptr {
		if found != 0 || !w32.IsWindowVisible(hwnd) {
			return 1
		}
		if _, windowPID := w32.GetWindowThreadProcessId(hwnd); uint32(windowPID) == pid {
			found = hwnd
			return 0
		}
		return 1
	})
	w32.EnumWindows(callback, 0)
	cachedWindowHandle = found
	return found
}
