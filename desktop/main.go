package main

import (
	"embed"
	"log"
	"os"
	"strings"

	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/events"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/auth"
	"cc.naihao/simplenotes/desktop/internal/folders"
	"cc.naihao/simplenotes/desktop/internal/localfile"
	"cc.naihao/simplenotes/desktop/internal/notes"
	"cc.naihao/simplenotes/desktop/internal/store"
	"cc.naihao/simplenotes/desktop/internal/syncsvc"
	"cc.naihao/simplenotes/desktop/internal/system"
)

// Wails 通过 embed 把前端构建产物打进二进制。
// 前端源码在 frontend/apps/desktop，构建后由 `pnpm sync:wails` 同步到这里。
//
//go:embed all:web/dist
var assets embed.FS

// 应用图标（品牌源图 assets/brand 的副本）。
// Windows 正常构建会用 `wails3 generate syso` 把 icon.ico 嵌进 exe 资源；
// 这里再内嵌一份 PNG 作为运行时兜底（直接用 `go build` / IDE 编译时没有 .syso，
// Wails 会回退到 application.Options.Icon），同时用于关于框与 Linux 窗口图标。
//
//go:embed build/appicon.png
var appIcon []byte

func main() {
	db, err := store.Open()
	if err != nil {
		log.Fatalf("打开本地数据库失败: %v", err)
	}
	defer db.Close()

	baseURL := os.Getenv("SIMPLE_NOTES_API_BASE")
	if baseURL == "" {
		baseURL = "http://localhost:8080/api"
	}

	// 允许按环境变量附加 WebView2 参数（POC 阶段用于内存 / 渲染对比）。
	// 例：SIMPLE_NOTES_WEBVIEW_ARGS="--disable-gpu"
	windowsOptions := application.WindowsOptions{}
	if args := os.Getenv("SIMPLE_NOTES_WEBVIEW_ARGS"); args != "" {
		windowsOptions.AdditionalBrowserArgs = strings.Fields(args)
	}

	apiService := api.New(db, baseURL)
	fileService := localfile.New(db)

	app := application.New(application.Options{
		Name:        "Simple Notes",
		Description: "随手记与博客写作桌面端",
		Icon:        appIcon,
		Services: []application.Service{
			application.NewService(apiService),
			application.NewService(auth.New(db)),
			application.NewService(syncsvc.New(db, apiService)),
			application.NewService(folders.New(db, apiService)),
			application.NewService(notes.New(db, apiService)),
			application.NewService(fileService),
			application.NewService(system.New(db, baseURL)),
		},
		Assets: application.AssetOptions{
			Handler: application.AssetFileServerFS(assets),
		},
		Windows: windowsOptions,
		Mac: application.MacOptions{
			ApplicationShouldTerminateAfterLastWindowClosed: true,
		},
	})

	window := app.Window.NewWithOptions(application.WebviewWindowOptions{
		Title:            "Simple Notes",
		Width:            1180,
		Height:           780,
		MinWidth:         900,
		MinHeight:        600,
		BackgroundColour: application.NewRGB(18, 18, 18),
		URL:              "/",
		EnableFileDrop:   true,
	})

	// 有未保存内容时先拦截关闭，由前端确认保存 / 放弃后再退出。
	window.RegisterHook(events.Common.WindowClosing, func(event *application.WindowEvent) {
		if fileService.ShouldBlockClose() {
			event.Cancel()
			window.EmitEvent("app:close-requested")
		}
	})

	// 原生拖拽事件由 Go 转发给前端：WebView 侧只监听 app:files-dropped。
	window.OnWindowEvent(events.Common.WindowFilesDropped, func(event *application.WindowEvent) {
		files := event.Context().DroppedFiles()
		if len(files) > 0 {
			window.EmitEvent("app:files-dropped", files)
		}
	})

	if err := app.Run(); err != nil {
		log.Fatal(err)
	}
}
