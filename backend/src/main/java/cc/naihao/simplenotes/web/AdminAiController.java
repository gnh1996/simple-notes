package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.site.PostSummaryService;
import cc.naihao.simplenotes.web.dto.AiStatusResponse;
import cc.naihao.simplenotes.web.dto.PostSummaryRequest;
import cc.naihao.simplenotes.web.dto.PostSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 辅助（docs/ai.md）：当前只有文章摘要。
 *
 * <p>权限复用文章创作 / 编辑码，不新增权限节点：AI 只是编辑器里的辅助动作，
 * 不引入新的数据读写能力。状态接口供前端首屏置灰按钮。
 */
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AdminAiController {

    private final PostSummaryService postSummaryService;

    @GetMapping("/status")
    @PreAuthorize("@authz.canAny('admin:post:create', 'admin:post:update')")
    public AiStatusResponse status() {
        return new AiStatusResponse(postSummaryService.enabled());
    }

    @PostMapping("/post-summary")
    @PreAuthorize("@authz.canAny('admin:post:create', 'admin:post:update')")
    public PostSummaryResponse postSummary(@Valid @RequestBody PostSummaryRequest request) {
        return new PostSummaryResponse(postSummaryService.summarize(
                SecurityUtils.currentUserId(), request.title(), request.content()));
    }
}
