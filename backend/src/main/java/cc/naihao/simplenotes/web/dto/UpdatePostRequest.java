package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 编辑文章草稿；kind / publishStatus / publishedRevisionId 不在请求中。 */
public record UpdatePostRequest(
        @NotNull(message = "缺少版本号")
        Long version,

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最长 200 个字符")
        String title,

        @NotNull(message = "正文不能为空")
        String content,

        @Size(max = 500, message = "摘要最长 500 个字符")
        String summary,

        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "slug 只能包含小写字母、数字和连字符")
        @Size(max = 200, message = "slug 最长 200 个字符")
        String slug,

        String coverAttachmentId,

        List<@Size(max = 32, message = "标签最长 32 个字符") String> tags,

        Boolean pinned,

        /** ISO-8601；null 表示不修改，空白表示清除 */
        String authoredAt) {
}
