package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 新建记事本。 */
public record CreateNoteRequest(
        /** 可选：客户端生成的 UUID（离线新建） */
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "id 必须是 UUID")
        String id,

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最长 200 个字符")
        String title,

        @NotNull(message = "正文不能为空")
        String content,

        String folderId,

        List<@Size(max = 32, message = "标签最长 32 个字符") String> tags,

        Boolean pinned) {
}
