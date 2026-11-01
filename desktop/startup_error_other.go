//go:build !windows

package main

import (
	"fmt"
	"os"
)

// showStartupError 在无图形壳可用的启动阶段退化为 stderr 输出；
// Linux / macOS 的图形对话框随三平台实机验收再补。
func showStartupError(title, message string) {
	fmt.Fprintf(os.Stderr, "%s: %s\n", title, message)
}
