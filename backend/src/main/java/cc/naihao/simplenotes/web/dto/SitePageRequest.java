package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新增 / 更新独立页面；保存即线上。 */
public record SitePageRequest(
        @NotBlank(message = "slug 不能为空")
        @Size(max = 64, message = "slug 最长 64 个字符")
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "slug 只能包含小写字母、数字与连字符")
        String slug,

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最长 200 个字符")
        String title,

        @NotBlank(message = "内容不能为空")
        String content,

        boolean visible,

        int sort) {
}
