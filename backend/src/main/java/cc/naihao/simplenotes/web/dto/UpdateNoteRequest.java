package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 编辑记事本；kind / 发布字段不在请求中。 */
public record UpdateNoteRequest(
        @NotNull(message = "缺少版本号")
        Long version,

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最长 200 个字符")
        String title,

        @NotBlank(message = "正文不能为空")
        String content,

        String folderId,

        List<@Size(max = 32, message = "标签最长 32 个字符") String> tags,

        Boolean pinned) {
}
