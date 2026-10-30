package cc.naihao.simplenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cc.naihao.simplenotes.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 公开博客排序上下文（docs/content-model.md §6.4）：authoring date、置顶、方向、上下篇、归档月视图。
 */
class BlogSortIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("列表 / 详情上下篇：displayAt 倒序、正序与置顶优先")
    void listAndNeighborsFollowSortContext() throws Exception {
        String token = rootToken();
        String suffix = uniqueSuffix();
        String tag = "排序-" + suffix;
        String oldSlug = "old-" + suffix.toLowerCase();
        String midSlug = "mid-" + suffix.toLowerCase();
        String newSlug = "new-" + suffix.toLowerCase();

        createPublishedPost(token, "旧-" + suffix, oldSlug, "2020-01-01T00:00:00Z",
                false, List.of(tag), "旧正文 " + suffix);
        createPublishedPost(token, "中-" + suffix, midSlug, "2021-01-01T00:00:00Z",
                true, List.of(tag), "中正文 " + suffix);
        createPublishedPost(token, "新-" + suffix, newSlug, "2022-01-01T00:00:00Z",
                false, List.of(tag), "新正文 " + suffix);
        String tagId = tagId(tag);

        JsonNode desc = publicList("?tagId=" + tagId
                + "&pinnedFirst=false&direction=desc&sortBy=displayAt&size=100");
        assertThat(desc.path("items")).hasSize(3);
        assertThat(slugAt(desc, 0)).isEqualTo(newSlug);
        assertThat(slugAt(desc, 1)).isEqualTo(midSlug);
        assertThat(slugAt(desc, 2)).isEqualTo(oldSlug);

        JsonNode midDesc = publicDetail(midSlug, tagId, "false", "desc");
        assertThat(midDesc.path("prevPost").path("slug").asString()).isEqualTo(newSlug);
        assertThat(midDesc.path("nextPost").path("slug").asString()).isEqualTo(oldSlug);

        JsonNode asc = publicList("?tagId=" + tagId
                + "&pinnedFirst=false&direction=asc&sortBy=displayAt&size=100");
        assertThat(slugAt(asc, 0)).isEqualTo(oldSlug);
        assertThat(slugAt(asc, 1)).isEqualTo(midSlug);
        assertThat(slugAt(asc, 2)).isEqualTo(newSlug);

        JsonNode midAsc = publicDetail(midSlug, tagId, "false", "asc");
        assertThat(midAsc.path("prevPost").path("slug").asString()).isEqualTo(oldSlug);
        assertThat(midAsc.path("nextPost").path("slug").asString()).isEqualTo(newSlug);

        JsonNode pinned = publicList("?tagId=" + tagId
                + "&pinnedFirst=true&direction=desc&sortBy=displayAt&size=100");
        assertThat(slugAt(pinned, 0)).isEqualTo(midSlug);
        assertThat(slugAt(pinned, 1)).isEqualTo(newSlug);
        assertThat(slugAt(pinned, 2)).isEqualTo(oldSlug);

        JsonNode newPinned = publicDetail(newSlug, tagId, "true", "desc");
        assertThat(newPinned.path("prevPost").path("slug").asString()).isEqualTo(midSlug);
        assertThat(newPinned.path("nextPost").path("slug").asString()).isEqualTo(oldSlug);
    }

    @Test
    @DisplayName("authoredAt：为空回落 firstPublishedAt；修改立即生效且不触发未发布更改")
    void authoredAtFallbackAndOperationalUpdate() throws Exception {
        String token = rootToken();
        String suffix = uniqueSuffix();
        String tag = "创作时间-" + suffix;
        String slug = "auth-" + suffix.toLowerCase();
        String id = createPublishedPost(token, "创作时间-" + suffix, slug, null, false,
                List.of(tag), "正文 " + suffix);

        JsonNode before = publicDetail(slug, null, "false", "desc");
        assertThat(before.path("authoredAt").isNull()).isTrue();
        assertThat(before.path("displayAt").asString())
                .isEqualTo(before.path("firstPublishedAt").asString());

        JsonNode admin = body(authedGet("/api/admin/posts/" + id, token)
                .andExpect(status().isOk()));
        List<String> tags = new ArrayList<>();
        admin.path("tags").forEach(item -> tags.add(item.path("name").asString()));

        Map<String, Object> update = new HashMap<>();
        update.put("version", admin.path("version").asLong());
        update.put("title", admin.path("title").asString());
        update.put("content", admin.path("content").asString());
        update.put("summary", admin.path("summary").asString());
        update.put("slug", admin.path("slug").asString());
        update.put("tags", tags);
        update.put("pinned", admin.path("pinned").asBoolean());
        update.put("authoredAt", "2019-05-06T07:08:09Z");

        JsonNode updated = body(authedPut("/api/admin/posts/" + id, token, update)
                .andExpect(status().isOk()));
        assertThat(updated.path("hasUnpublishedChanges").asBoolean()).isFalse();
        assertThat(updated.path("authoredAt").asString()).isEqualTo("2019-05-06T07:08:09Z");

        JsonNode after = publicDetail(slug, null, "false", "desc");
        assertThat(after.path("displayAt").asString()).isEqualTo("2019-05-06T07:08:09Z");
        assertThat(after.path("authoredAt").asString()).isEqualTo("2019-05-06T07:08:09Z");
    }

    @Test
    @DisplayName("搜索上下篇携带 q，只在搜索结果内相邻")
    void searchNeighborsStayInsideResults() throws Exception {
        String token = rootToken();
        String suffix = uniqueSuffix();
        String keyword = "needle" + suffix.toLowerCase();
        String tag = "搜索-" + suffix;
        String oldSlug = "search-old-" + suffix.toLowerCase();
        String newSlug = "search-new-" + suffix.toLowerCase();
        String otherSlug = "search-other-" + suffix.toLowerCase();

        createPublishedPost(token, "搜索旧-" + suffix, oldSlug, "2020-01-01T00:00:00Z",
                false, List.of(tag), keyword + " 旧正文");
        createPublishedPost(token, "搜索新-" + suffix, newSlug, "2021-01-01T00:00:00Z",
                false, List.of(tag), keyword + " 新正文");
        createPublishedPost(token, "搜索其他-" + suffix, otherSlug, "2022-01-01T00:00:00Z",
                false, List.of(tag), "完全无关的正文");

        JsonNode detail = body(mockMvc.perform(get("/api/blog/posts/" + newSlug)
                        .param("q", keyword)
                        .param("pinnedFirst", "false")
                        .param("direction", "desc")
                        .param("sortBy", "displayAt"))
                .andExpect(status().isOk()));
        assertThat(detail.path("prevPost").isNull()).isTrue();
        assertThat(detail.path("nextPost").path("slug").asString()).isEqualTo(oldSlug);
    }

    @Test
    @DisplayName("归档：月份索引按 displayAt，月内分页不截断")
    void archivesUseMonthIndexAndPagedMonthView() throws Exception {
        String token = rootToken();
        String suffix = uniqueSuffix();
        String tag = "归档-" + suffix;
        String jan1 = "archive-jan1-" + suffix.toLowerCase();
        String jan2 = "archive-jan2-" + suffix.toLowerCase();
        String feb = "archive-feb-" + suffix.toLowerCase();
        String nextYear = "archive-1902-" + suffix.toLowerCase();

        createPublishedPost(token, "归档一月一-" + suffix, jan1, "1901-01-05T00:00:00Z",
                false, List.of(tag), "正文1");
        createPublishedPost(token, "归档一月二-" + suffix, jan2, "1901-01-20T00:00:00Z",
                false, List.of(tag), "正文2");
        createPublishedPost(token, "归档二月-" + suffix, feb, "1901-02-05T00:00:00Z",
                false, List.of(tag), "正文3");
        createPublishedPost(token, "归档次年-" + suffix, nextYear, "1902-01-05T00:00:00Z",
                false, List.of(tag), "正文4");

        JsonNode archives = body(mockMvc.perform(get("/api/blog/archives")
                        .param("sortBy", "displayAt")
                        .param("direction", "asc"))
                .andExpect(status().isOk()));
        int janIndex = monthIndex(archives, 1901, 1);
        int febIndex = monthIndex(archives, 1901, 2);
        int nextIndex = monthIndex(archives, 1902, 1);
        assertThat(janIndex).isGreaterThanOrEqualTo(0);
        assertThat(febIndex).isGreaterThan(janIndex);
        assertThat(nextIndex).isGreaterThan(febIndex);
        assertThat(archives.get(janIndex).path("count").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode page1 = body(mockMvc.perform(get("/api/blog/posts")
                        .param("year", "1901")
                        .param("month", "1")
                        .param("page", "1")
                        .param("size", "1")
                        .param("pinnedFirst", "false")
                        .param("direction", "asc")
                        .param("sortBy", "displayAt"))
                .andExpect(status().isOk()));
        JsonNode page2 = body(mockMvc.perform(get("/api/blog/posts")
                        .param("year", "1901")
                        .param("month", "1")
                        .param("page", "2")
                        .param("size", "1")
                        .param("pinnedFirst", "false")
                        .param("direction", "asc")
                        .param("sortBy", "displayAt"))
                .andExpect(status().isOk()));
        assertThat(page1.path("total").asInt()).isEqualTo(2);
        assertThat(page1.path("items")).hasSize(1);
        assertThat(page2.path("items")).hasSize(1);
        assertThat(page1.path("items").get(0).path("slug").asString())
                .isNotEqualTo(page2.path("items").get(0).path("slug").asString());
        assertThat(page1.path("items").get(0).path("displayAt").asString()).startsWith("1901-01");
    }

    @Test
    @DisplayName("未知 sortBy / direction 返回 400")
    void rejectsUnknownSortValues() throws Exception {
        mockMvc.perform(get("/api/blog/posts").param("sortBy", "nope"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/blog/posts").param("direction", "sideways"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/blog/archives").param("sortBy", "nope"))
                .andExpect(status().isBadRequest());
    }

    private String createPublishedPost(String token, String title, String slug, String authoredAt,
            boolean pinned, List<String> tags, String content) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("title", title);
        request.put("content", content);
        request.put("summary", "摘要 " + slug);
        request.put("slug", slug);
        request.put("tags", tags);
        request.put("pinned", pinned);
        if (authoredAt != null) {
            request.put("authoredAt", authoredAt);
        }
        JsonNode created = body(authedPost("/api/admin/posts", token, request)
                .andExpect(status().isOk()));
        authedPost("/api/admin/posts/" + created.path("id").asString() + "/publish", token,
                Map.of("version", created.path("version").asLong()))
                .andExpect(status().isOk());
        return created.path("id").asString();
    }

    private JsonNode publicList(String query) throws Exception {
        return body(mockMvc.perform(get("/api/blog/posts" + query)).andExpect(status().isOk()));
    }

    private JsonNode publicDetail(String slug, String tagId, String pinnedFirst, String direction)
            throws Exception {
        var request = get("/api/blog/posts/" + slug)
                .param("pinnedFirst", pinnedFirst)
                .param("direction", direction)
                .param("sortBy", "displayAt");
        if (tagId != null) {
            request.param("tagId", tagId);
        }
        return body(mockMvc.perform(request).andExpect(status().isOk()));
    }

    private String tagId(String name) throws Exception {
        JsonNode tags = body(mockMvc.perform(get("/api/blog/tags")).andExpect(status().isOk()));
        for (JsonNode tag : tags) {
            if (name.equals(tag.path("name").asString())) {
                return tag.path("id").asString();
            }
        }
        throw new AssertionError("找不到标签：" + name);
    }

    private String slugAt(JsonNode page, int index) {
        return page.path("items").get(index).path("slug").asString();
    }

    private int monthIndex(JsonNode archives, int year, int month) {
        for (int i = 0; i < archives.size(); i++) {
            if (archives.get(i).path("year").asInt() == year
                    && archives.get(i).path("month").asInt() == month) {
                return i;
            }
        }
        return -1;
    }
}
