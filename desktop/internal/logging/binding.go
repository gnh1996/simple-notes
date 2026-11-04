package logging

// Binding 是暴露给前端的日志服务面：只开放写日志与诊断读取，
// 不把 Close / SetMinLevel 等内部维护方法暴露给 WebView。
type Binding struct {
	service *Service
}

// NewBinding 创建前端可见的日志服务。
func NewBinding(service *Service) *Binding {
	return &Binding{service: service}
}

// Write 记录一条前端日志；fields 是可选的结构化字段。
func (b *Binding) Write(level, event, message string, fields map[string]string) error {
	return b.service.Write(level, event, message, fields)
}

// LogDir 返回日志目录。
func (b *Binding) LogDir() string {
	return b.service.LogDir()
}

// LogPath 返回当前日志文件路径。
func (b *Binding) LogPath() string {
	return b.service.LogPath()
}

// Diagnostics 生成可复制的诊断摘要（不含令牌 / 正文）。
func (b *Binding) Diagnostics() string {
	return b.service.Diagnostics()
}

// OpenLogDir 在系统文件管理器中打开日志目录。
func (b *Binding) OpenLogDir() error {
	return b.service.OpenLogDir()
}
