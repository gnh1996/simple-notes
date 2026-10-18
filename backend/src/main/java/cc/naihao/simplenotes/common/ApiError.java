package cc.naihao.simplenotes.common;

/**
 * 统一错误响应体：{@code { "code": "<语义码>", "message": "<可读信息>" }}。
 *
 * <p>语义码见 {@code docs/auth-rbac.md} §8（命名为暂定，随 API 契约统一定稿）。
 */
public record ApiError(String code, String message) {
}
