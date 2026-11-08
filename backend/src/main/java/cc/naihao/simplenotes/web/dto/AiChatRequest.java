package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** AI 主题对话请求：SSE POST body（docs/theme-ai.md §2）。 */
@Schema(requiredProperties = { "prompt" })
public record AiChatRequest(
        /** 历史轮次（最多 10 轮，超出由前端裁剪） */
        @Size(max = 20, message = "历史轮次过多")
        List<@Valid AiChatTurn> history,

        /** 本轮用户指令 */
        @NotBlank(message = "prompt 不能为空")
        @Size(max = 4000, message = "prompt 最长 4000 字符")
        String prompt,

        /** 当前主题快照（后续轮增量修改的基准；首轮可空 = 从 starter 起） */
        @Size(max = 64 * 1024, message = "baseHtml 超出 64KB 上限")
        String baseHtml,

        /** 当前主题快照 CSS */
        @Size(max = 256 * 1024, message = "baseCss 超出 256KB 上限")
        String baseCss,

        /** 首轮=true 时后端附带 starter 全文（docs/theme-ai.md §3） */
        boolean includeStarter) {
}
