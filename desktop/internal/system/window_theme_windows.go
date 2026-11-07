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

// attachWindowHandle 注入主窗口原生句柄获取器（由 main 在创建窗口后注册）。
func attachWindowHandle(provider func() uintptr) {
	windowHandleProvider = provider
}

// windowHandleProvider 返回主窗口 HWND；Wails 在页面加载完成后才 Show 窗口，
// 首帧里 EnumWindows 看不到可见窗口，必须靠它把主题直接下发到 DWM，
// 否则深色主题的标题栏要手动切一次才会生效。
var windowHandleProvider func() uintptr

// cachedWindowHandle 缓存枚举结果：mainWindowHandle 只在主线程串行调用，不需要额外加锁。
var cachedWindowHandle w32.HWND

// mainWindowHandle 优先使用注入的句柄；拿不到时回退到枚举当前进程的可见顶层窗口。
func mainWindowHandle() w32.HWND {
	if windowHandleProvider != nil {
		if hwnd := w32.HWND(windowHandleProvider()); hwnd != 0 {
			return hwnd
		}
	}
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
