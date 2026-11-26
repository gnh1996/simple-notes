package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI 摘要请求（docs/ai.md §2.2）：由编辑器发送当前未保存内容，不要求文章先落库。
 * 正文按原始 Markdown 全文送模型；服务端只做长度上限保护，不重新解析 / 改写。
 */
@Schema(requiredProperties = { "title", "content" })
public record PostSummaryRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最长 200 个字符")
        String title,

        @NotBlank(message = "正文不能为空")
        @Size(max = 300_000, message = "正文过长，无法生成摘要")
        String content) {
}
