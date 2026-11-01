//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

var (
	user32DLL   = syscall.NewLazyDLL("user32.dll")
	messageBoxW = user32DLL.NewProc("MessageBoxW")
)

// showStartupError 在进入 Wails 事件循环之前用原生对话框报告启动失败。
// 此时 WebView 还没有创建，不能依赖前端弹窗。
func showStartupError(title, message string) {
	titlePtr, err := syscall.UTF16PtrFromString(title)
	if err != nil {
		return
	}
	messagePtr, err := syscall.UTF16PtrFromString(message)
	if err != nil {
		return
	}
	const (
		mbOK        = 0x00000000
		mbIconError = 0x00000010
	)
	_, _, _ = messageBoxW.Call(
		0,
		uintptr(unsafe.Pointer(messagePtr)),
		uintptr(unsafe.Pointer(titlePtr)),
		uintptr(mbOK|mbIconError),
	)
}
