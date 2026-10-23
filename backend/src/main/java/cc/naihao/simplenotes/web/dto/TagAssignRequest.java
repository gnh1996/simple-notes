package cc.naihao.simplenotes.web.dto;

import cc.naihao.simplenotes.domain.NoteKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 给一批内容当前草稿添加标签。 */
public record TagAssignRequest(
        @NotNull(message = "缺少内容类型")
        NoteKind kind,

        @NotEmpty(message = "请选择要分配的内容")
        List<@NotBlank(message = "内容 id 不能为空") String> contentIds) {
}
