//go:build !windows

package system

// applyWindowTheme 非 Windows 平台暂不处理原生标题栏主题。
func applyWindowTheme(bool) {}

// attachWindowHandle 非 Windows 平台无需原生窗口句柄。
func attachWindowHandle(func() uintptr) {}
