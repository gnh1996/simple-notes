package cc.naihao.simplenotes.web.dto;

/**
 * 导航项（公开）：{@code to} 由服务端按内置 key 派生，配置里不写死路径。
 */
public record NavItemResponse(String key, String label, String to) {
}
