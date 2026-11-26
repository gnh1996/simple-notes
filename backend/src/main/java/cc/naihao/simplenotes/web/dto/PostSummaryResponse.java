package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** AI 摘要结果（docs/ai.md §2.2）：纯文本，已由服务端清洗并裁剪到摘要长度。 */
@Schema(requiredProperties = { "summary" })
public record PostSummaryResponse(
        String summary) {
}
