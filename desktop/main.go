package main

import (
	"embed"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/events"

	"cc.naihao/simplenotes/desktop/internal/api"
	"cc.naihao/simplenotes/desktop/internal/auth"
	"cc.naihao/simplenotes/desktop/internal/folders"
	"cc.naihao/simplenotes/desktop/internal/localfile"
	"cc.naihao/simplenotes/desktop/internal/logging"
	"cc.naihao/simplenotes/desktop/internal/media"
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
	logService, logErr := logging.Init(system.DisplayVersion())
	if logErr != nil {
		log.Printf("初始化运行日志失败: %v", logErr)
	}
	defer logService.Close()
	logging.Info("app.start", "应用启动", map[string]string{
		"version":   system.Version,
		"commit":    system.Commit,
		"buildTime": system.BuildTime,
	})

	db, err := store.Open()
	if err != nil {
		// 迁移失败 / 库版本过新都在这里阻塞启动：先落日志，再弹原生对话框。
		logging.Error("db.migrate", "本地数据库初始化失败", map[string]string{"err": err.Error()})
		writeStartupLog(err)
		showStartupError("Simple Notes 无法启动", store.StartupMessage(err))
		os.Exit(1)
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
	mediaService := media.New(db, apiService)
	fileService := localfile.New(db, mediaService)

	app := application.New(application.Options{
		Name:        "Simple Notes",
		Description: "随手记与博客写作桌面端",
		Icon:        appIcon,
		Services: []application.Service{
			application.NewService(apiService),
			application.NewService(auth.New(db)),
			application.NewService(syncsvc.New(db, apiService)),
			application.NewService(folders.New(db, apiService)),
			application.NewService(notes.New(db, apiService, mediaService)),
			application.NewService(fileService),
			application.NewService(system.New(db, baseURL)),
			application.NewServiceWithOptions(mediaService, application.ServiceOptions{Route: "/__media"}),
			application.NewService(logging.NewBinding(logService)),
		},
		Assets: application.AssetOptions{
			Handler: application.AssetFileServerFS(assets),
			Middleware: func(next http.Handler) http.Handler {
				return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					if mediaService.HandleProxy(w, r) {
						return
					}
					next.ServeHTTP(w, r)
				})
			},
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
		// 关闭 WebView2 默认右键菜单（刷新 / 打印 / 检查）；应用内右键菜单由前端自行渲染，
		// 文本编辑控件的剪切 / 复制 / 粘贴也由前端接管（见 frontend/apps/desktop）。
		DefaultContextMenuDisabled: true,
	})

	// 首帧下发主题时窗口还没 Show（Wails 等页面加载完成才显示），
	// 注入原生句柄让 SetWindowTheme 不必依赖“可见窗口”也能生效。
	system.AttachWindowHandle(func() uintptr {
		return uintptr(window.NativeWindow())
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
		logging.Error("app.run", "应用退出异常", map[string]string{"err": err.Error()})
		log.Fatal(err)
	}
	logging.Info("app.stop", "应用退出", nil)
}

// writeStartupLog 把启动失败写入用户配置目录：GUI 进程通常没有可见的 stderr。
func writeStartupLog(err error) {
	dir, dirErr := os.UserConfigDir()
	if dirErr != nil {
		return
	}
	logPath := filepath.Join(dir, "simple-notes", "desktop-error.log")
	if mkErr := os.MkdirAll(filepath.Dir(logPath), 0o700); mkErr != nil {
		return
	}
	file, openErr := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if openErr != nil {
		return
	}
	defer file.Close()
	fmt.Fprintf(file, "[%s] %v\n", time.Now().Format(time.RFC3339), err)
}
