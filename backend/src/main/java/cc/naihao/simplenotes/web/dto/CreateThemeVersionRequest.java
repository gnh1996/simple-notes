package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增主题版本（不可变快照）。
 *
 * <p>结构 / 危险模式校验在服务层执行（docs/blog-runtime-theme.md §8）；
 * 这里只做体积与必填的快速拦截。
 */
public record CreateThemeVersionRequest(
        @NotBlank(message = "主题 HTML 不能为空")
        @Size(max = 64 * 1024, message = "主题 HTML 超出 64KB 上限")
        String html,

        @NotBlank(message = "主题 CSS 不能为空")
        @Size(max = 256 * 1024, message = "主题 CSS 超出 256KB 上限")
        String css,

        @NotNull(message = "缺少运行时契约版本")
        Integer contract) {
}
