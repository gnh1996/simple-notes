package cc.naihao.simplenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cc.naihao.simplenotes.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 标签管理集成测试（docs/content-model.md §3.3）：全局标签、使用量、改名 / 删除警告、
 * 给笔记 / 文章分配标签，以及发布快照下的“草稿 / 线上”差异。
 */
class TagAdminIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("标签管理：联想选项 → 使用量 → 改名影响已发布快照 → 分配 / 移除 → 删除警告")
    void tagManagementLifecycle() throws Exception {
        String token = rootToken();
        String suffix = uniqueSuffix();
        String tagA = "标签A-" + suffix;
        String tagB = "标签B-" + suffix;
        String noteTitle = "标签笔记-" + suffix;
        String postTitle = "标签文章-" + suffix;
        String slug = "tag-" + suffix.toLowerCase();

        var note = body(authedPost("/api/admin/notes", token, Map.of(
                "title", noteTitle, "content", "笔记正文", "tags", List.of(tagA)))
                .andExpect(status().isOk()));
        String noteId = note.path("id").asString();

        var post = body(authedPost("/api/admin/posts", token, Map.of(
                "title", postTitle, "content", "文章正文", "summary", "摘要",
                "slug", slug, "tags", List.of(tagA)))
                .andExpect(status().isOk()));
        String postId = post.path("id").asString();
        authedPost("/api/admin/posts/" + postId + "/publish", token,
                Map.of("version", post.path("version").asLong())).andExpect(status().isOk());

        // 编辑器联想能看到已有标签
        var options = body(authedGet("/api/admin/tags/options", token).andExpect(status().isOk()));
        assertThat(options.toString()).contains(tagA);

        // 管理列表：随手记 1 / 博客草稿 1 / 已发布快照 1
        var tags = body(authedGet("/api/admin/tags?keyword=" + tagA, token).andExpect(status().isOk()));
        assertThat(tags.path("total").asInt()).isEqualTo(1);
        var tagRow = tags.path("items").get(0);
        String tagId = tagRow.path("id").asString();
        assertThat(tagRow.path("noteCount").asLong()).isEqualTo(1);
        assertThat(tagRow.path("postCount").asLong()).isEqualTo(1);
        assertThat(tagRow.path("publishedCount").asLong()).isEqualTo(1);

        // 关联内容：笔记 / 文章都显示草稿 + 线上快照标记
        var noteContents = body(authedGet("/api/admin/tags/" + tagId + "/contents?kind=NOTE", token)
                .andExpect(status().isOk()));
        assertThat(noteContents.path("items").get(0).path("draftTagged").asBoolean()).isTrue();
        assertThat(noteContents.path("items").get(0).path("publishedTagged").asBoolean()).isFalse();
        var postContents = body(authedGet("/api/admin/tags/" + tagId + "/contents?kind=POST", token)
                .andExpect(status().isOk()));
        assertThat(postContents.path("items").get(0).path("draftTagged").asBoolean()).isTrue();
        assertThat(postContents.path("items").get(0).path("publishedTagged").asBoolean()).isTrue();

        // 改名：当前内容和已发布快照都显示新名称（tag_id 引用）
        String renamed = tagA + "-改";
        authedPut("/api/admin/tags/" + tagId, token, Map.of("name", renamed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(renamed));
        var publicAfterRename = body(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/blog/posts/" + slug))
                .andExpect(status().isOk()));
        assertThat(publicAfterRename.path("tags").toString()).contains(renamed);

        // 创建标签 B，并从标签管理页分配给笔记 / 已发布文章
        var tagBRow = body(authedPost("/api/admin/tags", token, Map.of("name", tagB))
                .andExpect(status().isOk()));
        String tagBId = tagBRow.path("id").asString();
        var postCandidates = body(authedGet("/api/admin/tags/" + tagBId + "/candidates?kind=POST", token)
                .andExpect(status().isOk()));
        assertThat(postCandidates.toString()).contains(postId);
        authedPost("/api/admin/tags/" + tagBId + "/contents", token, Map.of(
                "kind", "NOTE", "contentIds", List.of(noteId))).andExpect(status().isNoContent());
        authedPost("/api/admin/tags/" + tagBId + "/contents", token, Map.of(
                "kind", "POST", "contentIds", List.of(postId))).andExpect(status().isNoContent());

        var noteAfterAssign = body(authedGet("/api/admin/notes/" + noteId, token).andExpect(status().isOk()));
        assertThat(noteAfterAssign.path("tags").toString()).contains(tagB);
        var postAfterAssign = body(authedGet("/api/admin/posts/" + postId, token).andExpect(status().isOk()));
        assertThat(postAfterAssign.path("tags").toString()).contains(tagB);
        assertThat(postAfterAssign.path("hasUnpublishedChanges").asBoolean()).isTrue();

        // 线上快照不因草稿加标签而变化
        var publicBeforeRepublish = body(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/blog/posts/" + slug))
                .andExpect(status().isOk()));
        assertThat(publicBeforeRepublish.path("tags").toString()).doesNotContain(tagB);

        // 从当前草稿移除标签 B；线上仍未使用，不受影响
        authedDelete("/api/admin/tags/" + tagBId + "/contents/" + postId, token)
                .andExpect(status().isNoContent());
        var postAfterRemove = body(authedGet("/api/admin/posts/" + postId, token).andExpect(status().isOk()));
        assertThat(postAfterRemove.path("tags").toString()).doesNotContain(tagB);

        // 删除仍有使用量的标签：未 force 返回 409；force 后公开页标签消失
        authedDelete("/api/admin/tags/" + tagId, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TAG_IN_USE"));
        authedDelete("/api/admin/tags/" + tagId + "?force=true", token)
                .andExpect(status().isNoContent());
        var noteAfterDelete = body(authedGet("/api/admin/notes/" + noteId, token).andExpect(status().isOk()));
        assertThat(noteAfterDelete.path("tags").toString()).doesNotContain(renamed);
        var publicAfterDelete = body(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/blog/posts/" + slug))
                .andExpect(status().isOk()));
        assertThat(publicAfterDelete.path("tags").toString()).doesNotContain(renamed);
    }
}
