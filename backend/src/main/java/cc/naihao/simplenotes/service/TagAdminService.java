package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.Note;
import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.NoteRevision;
import cc.naihao.simplenotes.domain.NoteTag;
import cc.naihao.simplenotes.domain.PublishStatus;
import cc.naihao.simplenotes.domain.QNote;
import cc.naihao.simplenotes.domain.QNoteRevision;
import cc.naihao.simplenotes.domain.QNoteRevisionTag;
import cc.naihao.simplenotes.domain.QNoteTag;
import cc.naihao.simplenotes.domain.QTag;
import cc.naihao.simplenotes.domain.Tag;
import cc.naihao.simplenotes.repository.NoteRepository;
import cc.naihao.simplenotes.repository.NoteRevisionRepository;
import cc.naihao.simplenotes.repository.NoteTagRepository;
import cc.naihao.simplenotes.repository.TagRepository;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.TagAdminSummary;
import cc.naihao.simplenotes.web.dto.TagAssignRequest;
import cc.naihao.simplenotes.web.dto.TagBrief;
import cc.naihao.simplenotes.web.dto.TagContentCandidate;
import cc.naihao.simplenotes.web.dto.TagContentResponse;
import cc.naihao.simplenotes.web.dto.TagNameRequest;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签管理（docs/content-model.md §3.3）。
 *
 * <p>标签是全局分类：改名 / 删除会立即影响所有当前内容与已发布快照的标签展示。
 * 给已发布文章分配 / 移除标签只改当前草稿，线上仍需“更新发布”。
 */
@Service
@RequiredArgsConstructor
public class TagAdminService {

    private static final int MAX_TAG_LENGTH = 32;

    private final TagRepository tagRepository;
    private final NoteRepository noteRepository;
    private final NoteRevisionRepository revisionRepository;
    private final NoteTagRepository noteTagRepository;
    private final PublishService publishService;
    private final JPAQueryFactory queryFactory;

    @Transactional(readOnly = true)
    public List<TagBrief> options() {
        return tagRepository.findAllByOrderByNameAsc().stream()
                .map(tag -> new TagBrief(tag.getId(), tag.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<TagAdminSummary> list(int page, int size, String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        QTag tag = QTag.tag;
        BooleanExpression predicate = keyword == null || keyword.isBlank()
                ? tag.id.isNotNull()
                : tag.name.containsIgnoreCase(keyword.trim());

        List<Tag> rows = queryFactory.selectFrom(tag)
                .where(predicate)
                .orderBy(tag.name.asc())
                .offset((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .fetch();
        Long total = queryFactory.select(tag.count()).from(tag).where(predicate).fetchOne();
        Map<String, Usage> usages = usageByIds(rows.stream().map(Tag::getId).toList());
        List<TagAdminSummary> items = rows.stream()
                .map(row -> toSummary(row, usages.getOrDefault(row.getId(), Usage.EMPTY)))
                .toList();
        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional
    public TagAdminSummary create(TagNameRequest request) {
        String name = normalizeName(request.name());
        if (tagRepository.findByNameIgnoreCase(name).isPresent()) {
            throw ApiException.conflict("标签名称已存在");
        }
        Tag tag = new Tag();
        tag.setName(name);
        tag.setCreateBy(SecurityUtils.currentUserId());
        tagRepository.save(tag);
        return toSummary(tag, Usage.EMPTY);
    }

    @Transactional
    public TagAdminSummary rename(String id, TagNameRequest request) {
        Tag tag = requireTag(id);
        String name = normalizeName(request.name());
        Optional<Tag> existing = tagRepository.findByNameIgnoreCase(name);
        if (existing.isPresent() && !existing.get().getId().equals(id)) {
            throw ApiException.conflict("标签名称已存在");
        }
        tag.setName(name);
        tag.setUpdateBy(SecurityUtils.currentUserId());
        tagRepository.save(tag);
        return toSummary(tag, usageByIds(List.of(id)).getOrDefault(id, Usage.EMPTY));
    }

    /**
     * 删除标签：软删除；清理当前草稿关联，保留发布快照关联（公开查询会过滤已删除标签）。
     * 有使用量时必须 force=true，前端在展示警告并由用户确认后重试。
     */
    @Transactional
    public void delete(String id, boolean force) {
        Tag tag = requireTag(id);
        Usage usage = usageByIds(List.of(id)).getOrDefault(id, Usage.EMPTY);
        if (!force && usage.total() > 0) {
            throw ApiException.tagInUse("标签仍关联 " + usage.noteCount + " 篇随手记、"
                    + usage.postCount + " 篇博客（其中 " + usage.publishedCount
                    + " 篇已发布）；确认后请带 force=true 重试");
        }

        List<NoteTag> links = noteTagRepository.findByTagId(id);
        List<String> noteIds = links.stream().map(NoteTag::getNoteId).distinct().toList();
        noteTagRepository.deleteAll(links);
        noteTagRepository.flush();
        touchNotes(noteIds);

        tag.markDeleted(SecurityUtils.currentUserId());
        tag.setUpdateBy(SecurityUtils.currentUserId());
        tagRepository.save(tag);
    }

    @Transactional(readOnly = true)
    public PageResponse<TagContentResponse> contents(String tagId, NoteKind kind, int page, int size,
            String keyword) {
        requireTag(tagId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        List<TagContentResponse> all = new ArrayList<>();

        if (kind == NoteKind.NOTE) {
            for (Note note : currentNotes(tagId, kind, keyword)) {
                all.add(toContent(note, true, false));
            }
        } else {
            List<Note> current = currentNotes(tagId, kind, keyword);
            List<Note> published = publishedNotes(tagId, keyword);
            Map<String, Note> merged = new LinkedHashMap<>();
            current.forEach(note -> merged.put(note.getId(), note));
            published.forEach(note -> merged.putIfAbsent(note.getId(), note));
            Map<String, Boolean> publishedIds = published.stream()
                    .collect(Collectors.toMap(Note::getId, note -> true, (a, b) -> a));
            Map<String, Boolean> draftIds = current.stream()
                    .collect(Collectors.toMap(Note::getId, note -> true, (a, b) -> a));
            for (Note note : merged.values()) {
                all.add(toContent(note, draftIds.containsKey(note.getId()),
                        publishedIds.containsKey(note.getId())));
            }
        }

        all.sort(Comparator.comparing(TagContentResponse::updateTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new PageResponse<>(all.subList(from, to), all.size(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public PageResponse<TagContentCandidate> candidates(String tagId, NoteKind kind, int page, int size,
            String keyword) {
        requireTag(tagId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        QNote note = QNote.note;
        QNoteTag link = QNoteTag.noteTag;

        BooleanExpression predicate = note.kind.eq(kind)
                .and(note.deleteFlag.eq((short) 0))
                .and(JPAExpressions.selectOne()
                        .from(link)
                        .where(link.noteId.eq(note.id), link.tagId.eq(tagId))
                        .notExists());
        if (keyword != null && !keyword.isBlank()) {
            predicate = predicate.and(note.title.containsIgnoreCase(keyword.trim()));
        }

        List<Note> rows = queryFactory.selectFrom(note)
                .where(predicate)
                .orderBy(note.updateTime.coalesce(note.createTime).desc())
                .offset((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .fetch();
        Long total = queryFactory.select(note.count()).from(note).where(predicate).fetchOne();
        List<TagContentCandidate> items = rows.stream()
                .map(this::toCandidate)
                .toList();
        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    /** 给一批内容当前草稿添加标签；已发布文章的线上快照不变，需更新发布。 */
    @Transactional
    public void assign(String tagId, TagAssignRequest request) {
        Tag tag = requireTag(tagId);
        List<String> contentIds = request.contentIds().stream().distinct().toList();
        List<Note> notes = noteRepository.findAllById(contentIds);
        if (notes.size() != contentIds.size()) {
            throw ApiException.badRequest("存在无效的内容 id");
        }
        if (notes.stream().anyMatch(note -> note.getKind() != request.kind())) {
            throw ApiException.badRequest("内容类型与请求不一致");
        }

        String operator = SecurityUtils.currentUserId();
        List<Note> touched = new ArrayList<>();
        for (Note note : notes) {
            if (noteTagRepository.findByNoteIdAndTagId(note.getId(), tag.getId()).isPresent()) {
                continue;
            }
            noteTagRepository.save(new NoteTag(note.getId(), tag.getId(), operator));
            touchNote(note, operator);
            touched.add(note);
        }
        noteRepository.saveAll(touched);
    }

    /** 从内容当前草稿移除标签；已发布快照需更新发布后才会移除。 */
    @Transactional
    public void remove(String tagId, String contentId) {
        Tag tag = requireTag(tagId);
        Note note = noteRepository.findById(contentId)
                .orElseThrow(() -> ApiException.notFound("内容不存在"));
        NoteTag link = noteTagRepository.findByNoteIdAndTagId(contentId, tag.getId())
                .orElseThrow(() -> ApiException.conflict(
                        "当前草稿未使用该标签；如线上快照仍使用，请更新发布后移除"));
        noteTagRepository.delete(link);
        noteTagRepository.flush();
        String operator = SecurityUtils.currentUserId();
        touchNote(note, operator);
        noteRepository.save(note);
    }

    private List<Note> currentNotes(String tagId, NoteKind kind, String keyword) {
        QNote note = QNote.note;
        QNoteTag link = QNoteTag.noteTag;
        BooleanExpression predicate = link.tagId.eq(tagId)
                .and(link.noteId.eq(note.id))
                .and(note.kind.eq(kind))
                .and(note.deleteFlag.eq((short) 0));
        if (keyword != null && !keyword.isBlank()) {
            predicate = predicate.and(note.title.containsIgnoreCase(keyword.trim()));
        }
        return queryFactory.select(note)
                .from(link, note)
                .where(predicate)
                .orderBy(note.updateTime.coalesce(note.createTime).desc())
                .fetch();
    }

    private List<Note> publishedNotes(String tagId, String keyword) {
        QNote note = QNote.note;
        QNoteRevision revision = QNoteRevision.noteRevision;
        QNoteRevisionTag revisionTag = QNoteRevisionTag.noteRevisionTag;
        BooleanExpression predicate = revisionTag.tagId.eq(tagId)
                .and(revisionTag.revisionId.eq(revision.id))
                .and(revision.id.eq(note.publishedRevisionId))
                .and(note.kind.eq(NoteKind.POST))
                .and(note.publishStatus.eq(PublishStatus.PUBLISHED))
                .and(note.deleteFlag.eq((short) 0));
        if (keyword != null && !keyword.isBlank()) {
            predicate = predicate.and(note.title.containsIgnoreCase(keyword.trim()));
        }
        return queryFactory.select(note)
                .from(revisionTag, revision, note)
                .where(predicate)
                .orderBy(note.updateTime.coalesce(note.createTime).desc())
                .fetch();
    }

    private Map<String, Usage> usageByIds(List<String> tagIds) {
        if (tagIds.isEmpty()) {
            return Map.of();
        }
        Map<String, long[]> counts = new LinkedHashMap<>();
        QNote note = QNote.note;
        QNoteTag link = QNoteTag.noteTag;
        List<Tuple> current = queryFactory
                .select(link.tagId, note.kind, note.id.countDistinct())
                .from(link, note)
                .where(link.noteId.eq(note.id),
                        note.deleteFlag.eq((short) 0),
                        link.tagId.in(tagIds))
                .groupBy(link.tagId, note.kind)
                .fetch();
        for (Tuple row : current) {
            long[] value = counts.computeIfAbsent(row.get(link.tagId), key -> new long[3]);
            long count = row.get(2, Long.class) == null ? 0 : row.get(2, Long.class);
            if (row.get(note.kind) == NoteKind.NOTE) {
                value[0] = count;
            } else {
                value[1] = count;
            }
        }

        QNoteRevision revision = QNoteRevision.noteRevision;
        QNoteRevisionTag revisionTag = QNoteRevisionTag.noteRevisionTag;
        List<Tuple> published = queryFactory
                .select(revisionTag.tagId, note.id.countDistinct())
                .from(revisionTag, revision, note)
                .where(revisionTag.revisionId.eq(revision.id),
                        revision.id.eq(note.publishedRevisionId),
                        note.kind.eq(NoteKind.POST),
                        note.publishStatus.eq(PublishStatus.PUBLISHED),
                        note.deleteFlag.eq((short) 0),
                        revisionTag.tagId.in(tagIds))
                .groupBy(revisionTag.tagId)
                .fetch();
        for (Tuple row : published) {
            long[] value = counts.computeIfAbsent(row.get(revisionTag.tagId), key -> new long[3]);
            value[2] = row.get(1, Long.class) == null ? 0 : row.get(1, Long.class);
        }

        Map<String, Usage> result = new LinkedHashMap<>();
        for (String id : tagIds) {
            long[] value = counts.getOrDefault(id, new long[3]);
            result.put(id, new Usage(value[0], value[1], value[2]));
        }
        return result;
    }

    private TagContentResponse toContent(Note note, boolean draftTagged, boolean publishedTagged) {
        return new TagContentResponse(note.getId(), note.getKind(), note.getTitle(),
                note.getPublishStatus(), hasChanges(note), draftTagged, publishedTagged,
                note.getUpdateTime());
    }

    private TagContentCandidate toCandidate(Note note) {
        return new TagContentCandidate(note.getId(), note.getKind(), note.getTitle(),
                note.getPublishStatus(), hasChanges(note), note.getUpdateTime());
    }

    private boolean hasChanges(Note note) {
        if (note.getKind() != NoteKind.POST) {
            return false;
        }
        if (note.getPublishedRevisionId() == null) {
            return true;
        }
        NoteRevision revision = revisionRepository.findById(note.getPublishedRevisionId()).orElse(null);
        return publishService.hasChanges(note, revision);
    }

    private void touchNotes(List<String> noteIds) {
        if (noteIds.isEmpty()) {
            return;
        }
        String operator = SecurityUtils.currentUserId();
        List<Note> notes = noteRepository.findAllById(noteIds);
        notes.forEach(note -> touchNote(note, operator));
        noteRepository.saveAll(notes);
    }

    private void touchNote(Note note, String operator) {
        note.setUpdateBy(operator);
        if (note.getKind() == NoteKind.POST) {
            note.setDraftModifiedAt(Instant.now());
        } else {
            note.setUpdateTime(Instant.now());
        }
    }

    private Tag requireTag(String id) {
        return tagRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("标签不存在"));
    }

    private String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isBlank()) {
            throw ApiException.badRequest("标签名称不能为空");
        }
        if (name.length() > MAX_TAG_LENGTH) {
            throw ApiException.badRequest("标签最长 " + MAX_TAG_LENGTH + " 个字符");
        }
        return name;
    }

    private TagAdminSummary toSummary(Tag tag, Usage usage) {
        return new TagAdminSummary(tag.getId(), tag.getName(), usage.noteCount, usage.postCount,
                usage.publishedCount, tag.getCreateTime(), tag.getUpdateTime());
    }

    private record Usage(long noteCount, long postCount, long publishedCount) {
        private static final Usage EMPTY = new Usage(0, 0, 0);

        long total() {
            return noteCount + postCount + publishedCount;
        }
    }
}
