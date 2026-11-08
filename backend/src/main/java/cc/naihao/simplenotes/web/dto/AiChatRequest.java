package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** AI 主题对话请求：SSE POST body（docs/theme-ai.md §2）。 */
@Schema(requiredProperties = { "prompt" })
public record AiChatRequest(
        /**
         * 抽屉会话 id（前端每段对话生成一个 UUID；后端 ChatMemory / 当前主题 conversationId 的一部分）。
         * 为空时回落 "default"（兼容旧前端）。
         */
        @Size(max = 64, message = "sessionId 过长")
        String sessionId,

        /**
         * 当前主题 id（可选）：参与 conversationId，切换主题即换一段会话；
         * 空值表示未选中主题（从默认主题 / 从零生成）。
         */
        @Size(max = 64, message = "themeId 过长")
        String themeId,

        /** 历史轮次（仅作种子：后端 memory 未命中时一次性写入，命中时忽略） */
        @Size(max = 20, message = "历史轮次过多")
        List<@Valid AiChatTurn> history,

        /** 本轮用户指令 */
        @NotBlank(message = "prompt 不能为空")
        @Size(max = 4000, message = "prompt 最长 4000 字符")
        String prompt,

        /** 首轮完整生成时的参考主题（已有服务端当前主题时忽略；可空 = 从 starter 起） */
        @Size(max = 64 * 1024, message = "baseHtml 超出 64KB 上限")
        String baseHtml,

        /** 同上 CSS */
        @Size(max = 256 * 1024, message = "baseCss 超出 256KB 上限")
        String baseCss,

        /** 无当前主题时是否附带 starter 全文作为契约参考（docs/theme-ai.md §2.1） */
        boolean includeStarter) {
}
