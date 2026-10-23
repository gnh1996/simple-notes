package cc.naihao.simplenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cc.naihao.simplenotes.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 内容模块集成测试（docs/content-model.md §4 / §5 / §6）。
 *
 * <p>重点：笔记 / 文章隔离、发布快照、编辑不改变线上、更新发布、下架、删除恢复、公开过滤。
 */
class ContentApiIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("笔记生命周期：创建 → 编辑 → 列表 → 转博客；转换后不再出现在笔记列表")
    void noteLifecycleAndPromote() throws Exception {
        String token = rootToken();
        String suffix = uniqueSuffix();
        String title = "内容测试笔记-" + suffix;

        var created = body(authedPost("/api/admin/notes", token, Map.of(
                "title", title,
                "content", "# 正文\n\n随手记内容",
                "tags", List.of("测试", "笔记"),
                "pinned", true)).andExpect(status().isOk()));
        String noteId = created.path("id").asString();
        long version = created.path("version").asLong();

        var listed = body(authedGet("/api/admin/notes?keyword=" + title, token).andExpect(status().isOk()));
        assertThat(listed.path("total").asInt()).isEqualTo(1);
        assertThat(listed.path("items").get(0).path("title").asString()).isEqualTo(title);
        assertThat(listed.path("items").get(0).path("tags")).hasSize(2);

        // 版本冲突
        authedPut("/api/admin/notes/" + noteId, token, Map.of(
                "version", version - 1, "title", title, "content", "x", "tags", List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        var updated = body(authedPut("/api/admin/notes/" + noteId, token, Map.of(
                "version", version,
                "title", title + "-改",
                "content", "# 正文\n\n改过",
                "tags", List.of("测试"),
                "pinned", false,
                "status", "ACTIVE")).andExpect(status().isOk()));
        assertThat(updated.path("version").asLong()).isGreaterThan(version);

        var post = body(authedPost("/api/admin/notes/" + noteId + "/promote", token, null)
                .andExpect(status().isOk()));
        assertThat(post.path("publishStatus").asString()).isEqualTo("DRAFT");
        String postId = post.path("id").asString();

        var notesAfter = body(authedGet("/api/admin/notes?keyword=" + title, token).andExpect(status().isOk()));
        assertThat(notesAfter.path("total").asInt()).isZero();

        // 文章列表（草稿没有 publishedRevisionId，验证空指针路径）；笔记接口不可访问该 id
        var drafts = body(authedGet("/api/admin/posts?keyword=" + title, token).andExpect(status().isOk()));
        assertThat(drafts.path("total").asInt()).isEqualTo(1);
        assertThat(drafts.path("items").get(0).path("hasUnpublishedChanges").asBoolean()).isTrue();
        authedGet("/api/admin/posts/" + postId, token).andExpect(status().isOk());
        authedGet("/api/admin/notes/" + postId, token).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("发布快照：编辑已发布文章不改变线上，更新发布后才生效；下架 / 删除 / 恢复")
    void publishSnapshotAndLifecycle() throws Exception {
        String token = rootToken();
        String suffix = uniqueSuffix();
        String slug = "it-" + suffix.toLowerCase();

        var post = body(authedPost("/api/admin/posts", token, Map.of(
                "title", "快照测试-" + suffix,
                "content", "# 第一版\n\n线上旧内容",
                "summary", "第一版摘要",
                "slug", slug,
                "tags", List.of("快照测试"),
                "pinned", false)).andExpect(status().isOk()));
        String postId = post.path("id").asString();
        long version = post.path("version").asLong();

        var published = body(authedPost("/api/admin/posts/" + postId + "/publish", token,
                Map.of("version", version)).andExpect(status().isOk()));
        assertThat(published.path("publishStatus").asString()).isEqualTo("PUBLISHED");
        assertThat(published.path("hasUnpublishedChanges").asBoolean()).isFalse();
        assertThat(published.path("slug").asString()).isEqualTo(slug);

        var publicOld = body(mockMvc.perform(get("/api/blog/posts/" + slug))
                .andExpect(status().isOk()));
        assertThat(publicOld.path("content").asString()).contains("线上旧内容");
        assertThat(publicOld.path("title").asString()).isEqualTo("快照测试-" + suffix);

        // 编辑已发布文章：线上仍读旧快照
        long publishedVersion = published.path("version").asLong();
        var edited = body(authedPut("/api/admin/posts/" + postId, token, Map.of(
                "version", publishedVersion,
                "title", "快照测试-" + suffix + "-新",
                "content", "# 第二版\n\n未发布新内容",
                "summary", "第二版摘要",
                "slug", slug,
                "tags", List.of("快照测试", "新标签"),
                "pinned", false)).andExpect(status().isOk()));
        assertThat(edited.path("hasUnpublishedChanges").asBoolean()).isTrue();

        var publicStillOld = body(mockMvc.perform(get("/api/blog/posts/" + slug))
                .andExpect(status().isOk()));
        assertThat(publicStillOld.path("content").asString()).contains("线上旧内容");
        assertThat(publicStillOld.path("title").asString()).doesNotContain("-新");

        // 更新发布：线上切到新快照，firstPublishedAt 不变
        var updated = body(authedPost("/api/admin/posts/" + postId + "/publish", token,
                Map.of("version", edited.path("version").asLong())).andExpect(status().isOk()));
        assertThat(updated.path("hasUnpublishedChanges").asBoolean()).isFalse();
        var publicNew = body(mockMvc.perform(get("/api/blog/posts/" + slug))
                .andExpect(status().isOk()));
        assertThat(publicNew.path("content").asString()).contains("未发布新内容");
        assertThat(publicNew.path("firstPublishedAt").asString())
                .isEqualTo(publicOld.path("firstPublishedAt").asString());

        // slug 已锁定
        authedPut("/api/admin/posts/" + postId, token, Map.of(
                "version", updated.path("version").asLong(),
                "title", "快照测试-" + suffix + "-新",
                "content", "# 第二版\n\n未发布新内容",
                "summary", "第二版摘要",
                "slug", "other-" + suffix,
                "tags", List.of("快照测试"),
                "pinned", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // 下架：公开 404
        authedPost("/api/admin/posts/" + postId + "/unpublish", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishStatus").value("UNPUBLISHED"));
        mockMvc.perform(get("/api/blog/posts/" + slug)).andExpect(status().isNotFound());

        // 删除 → 回收站 → 恢复为 UNPUBLISHED
        authedDelete("/api/admin/posts/" + postId, token).andExpect(status().isNoContent());
        var trash = body(authedGet("/api/admin/posts/trash", token).andExpect(status().isOk()));
        assertThat(trash.toString()).contains(postId);
        authedPost("/api/admin/posts/" + postId + "/restore", token, null)
                .andExpect(status().isNoContent());
        authedGet("/api/admin/posts/" + postId, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishStatus").value("UNPUBLISHED"));
        mockMvc.perform(get("/api/blog/posts/" + slug)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("公开列表 / 标签 / 归档只包含已发布快照；笔记永不进入公开接口")
    void publicProjection() throws Exception {
        String token = rootToken();
        String suffix = uniqueSuffix();
        String slug = "pub-" + suffix.toLowerCase();
        String tag = "公开标签-" + suffix;

        var post = body(authedPost("/api/admin/posts", token, Map.of(
                "title", "公开测试-" + suffix,
                "content", "公开正文 " + suffix,
                "summary", "公开摘要",
                "slug", slug,
                "tags", List.of(tag),
                "pinned", true)).andExpect(status().isOk()));
        authedPost("/api/admin/posts/" + post.path("id").asString() + "/publish", token,
                Map.of("version", post.path("version").asLong())).andExpect(status().isOk());

        var list = body(mockMvc.perform(get("/api/blog/posts?page=1&size=100"))
                .andExpect(status().isOk()));
        assertThat(list.path("items").toString()).contains(slug);

        var tags = body(mockMvc.perform(get("/api/blog/tags")).andExpect(status().isOk()));
        assertThat(tags.toString()).contains(tag);

        var search = body(mockMvc.perform(get("/api/blog/search").param("q", suffix))
                .andExpect(status().isOk()));
        assertThat(search.path("total").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(search.path("items").get(0).path("slug").asString()).isEqualTo(slug);

        // 笔记不会出现在公开搜索中
        authedPost("/api/admin/notes", token, Map.of(
                "title", "私有笔记-" + suffix,
                "content", "私有正文 " + suffix,
                "tags", List.of(tag))).andExpect(status().isOk());
        var searchAfter = body(mockMvc.perform(get("/api/blog/search").param("q", "私有正文-" + suffix))
                .andExpect(status().isOk()));
        assertThat(searchAfter.path("total").asInt()).isZero();
    }

    @Test
    @DisplayName("slug 永久保留：软删除文章后仍不可复用")
    void slugIsReservedAfterSoftDelete() throws Exception {
        String token = rootToken();
        String slug = "gone-" + uniqueSuffix().toLowerCase();

        var post = body(authedPost("/api/admin/posts", token, Map.of(
                "title", "slug 保留测试",
                "content", "正文",
                "summary", "摘要",
                "slug", slug,
                "tags", List.of())).andExpect(status().isOk()));
        authedDelete("/api/admin/posts/" + post.path("id").asString(), token)
                .andExpect(status().isNoContent());

        authedPost("/api/admin/posts", token, Map.of(
                "title", "slug 复用测试",
                "content", "正文",
                "summary", "摘要",
                "slug", slug,
                "tags", List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
    }

    @Test
    @DisplayName("媒体上传 / 公开读取 / 删除")
    void mediaUploadAndRead() throws Exception {
        String token = rootToken();
        MockMultipartFile file = new MockMultipartFile("file", "pixel.png", "image/png",
                new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 });

        MvcResult upload = mockMvc.perform(multipart("/api/admin/media")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andReturn();
        String id = objectMapper.readTree(upload.getResponse().getContentAsString()).path("id").asString();

        mockMvc.perform(get("/api/media/" + id))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .isEqualTo("image/png"));

        authedDelete("/api/admin/media/" + id, token).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/media/" + id)).andExpect(status().isNotFound());
    }
}
