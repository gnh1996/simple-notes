package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AI 摘要的输入保护与输出清洗（docs/ai.md §3）。 */
class PostSummaryPromptTest {

    @Test
    @DisplayName("输出清洗：去 fence、前缀、包裹引号并折叠空白")
    void sanitizeStripsModelDecorations() {
        String raw = "```\n摘要： “这是一段  测试摘要。” \n```";
        assertThat(PostSummaryPrompt.sanitize(raw)).isEqualTo("这是一段 测试摘要。");
    }

    @Test
    @DisplayName("输出清洗：短结果原样保留，不补省略号")
    void sanitizeKeepsShortText() {
        String text = "这是一段完整的摘要，长度远小于上限。";
        assertThat(PostSummaryPrompt.sanitize(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("输出清洗：超长时在句末裁剪并补省略号")
    void sanitizeClampsAtSentenceBoundary() {
        String raw = "这是第一句话。".repeat(40);
        String summary = PostSummaryPrompt.sanitize(raw);
        assertThat(summary).startsWith("这是第一句话。").endsWith("…");
        assertThat(summary.length()).isLessThanOrEqualTo(PostSummaryPrompt.MAX_OUTPUT_CHARS + 1);
        assertThat(summary.length()).isGreaterThanOrEqualTo(PostSummaryPrompt.MAX_OUTPUT_CHARS - 20);
    }

    @Test
    @DisplayName("输出清洗：单句超长时回退到逗号，且不保留悬空标点")
    void sanitizeFallsBackToComma() {
        String raw = "这是一句非常长的话，".repeat(30);
        String summary = PostSummaryPrompt.sanitize(raw);
        assertThat(summary).endsWith("…");
        assertThat(summary).doesNotContain("，…");
        assertThat(summary.length()).isLessThanOrEqualTo(PostSummaryPrompt.MAX_OUTPUT_CHARS + 1);
    }

    @Test
    @DisplayName("输入保护：未超限原样返回；超限保留首尾并标注省略")
    void clampInputKeepsHeadAndTail() {
        String shortText = "# 标题\n\n正文";
        assertThat(PostSummaryPrompt.clampInput(shortText)).isEqualTo(shortText);

        String longText = "A".repeat(40_000) + "TAIL";
        String clamped = PostSummaryPrompt.clampInput(longText);
        assertThat(clamped).startsWith("A".repeat(20_000));
        assertThat(clamped).endsWith("TAIL");
        assertThat(clamped).contains("中间省略");
        assertThat(clamped.length()).isLessThan(longText.length());
    }

    @Test
    @DisplayName("会话头：稳定、定长小写十六进制，且随用户变化")
    void sessionIdIsStableAndScoped() {
        String a = PostSummaryPrompt.sessionId("user-a");
        assertThat(a).isEqualTo(PostSummaryPrompt.sessionId("user-a"));
        assertThat(a).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(PostSummaryPrompt.sessionId("user-b")).isNotEqualTo(a);
    }
}
