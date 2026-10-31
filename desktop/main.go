package main

import (
	"embed"
	"log"
	"os"
	"strings"

	"github.com/wailsapp/wails/v3/pkg/application"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/auth"
	"cc.naihao/simplenotes/desktop/internal/folders"
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

	app := application.New(application.Options{
		Name:        "Simple Notes",
		Description: "随手记与博客写作桌面端",
		Services: []application.Service{
			application.NewService(apiService),
			application.NewService(auth.New(db)),
			application.NewService(syncsvc.New(db, apiService)),
			application.NewService(folders.New(db, apiService)),
			application.NewService(notes.New(db, apiService)),
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

	app.Window.NewWithOptions(application.WebviewWindowOptions{
		Title:            "Simple Notes",
		Width:            1180,
		Height:           780,
		MinWidth:         900,
		MinHeight:        600,
		BackgroundColour: application.NewRGB(18, 18, 18),
		URL:              "/",
	})

	if err := app.Run(); err != nil {
		log.Fatal(err)
	}
}
