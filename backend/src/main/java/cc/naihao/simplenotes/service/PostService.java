package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.Note;
import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.NoteRevision;
import cc.naihao.simplenotes.domain.PublishStatus;
import cc.naihao.simplenotes.domain.QNote;
import cc.naihao.simplenotes.repository.NoteRepository;
import cc.naihao.simplenotes.repository.NoteRevisionRepository;
import cc.naihao.simplenotes.security.AuthPrincipal;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.CreatePostRequest;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.PostDetail;
import cc.naihao.simplenotes.web.dto.PostSummary;
import cc.naihao.simplenotes.web.dto.TagBrief;
import cc.naihao.simplenotes.web.dto.UpdatePostRequest;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 博客文章服务（docs/content-model.md §6.2）：只操作 {@code kind=POST}。
 *
 * <p>后台列表 / 详情读取当前草稿；公开读取由 {@link BlogService} 从发布快照组装。
 */
@Service
@RequiredArgsConstructor
public class PostService {

    private final NoteRepository noteRepository;
    private final NoteRevisionRepository revisionRepository;
    private final TagService tagService;
    private final AttachmentService attachmentService;
    private final PublishService publishService;
    private final SlugService slugService;
    private final JPAQueryFactory queryFactory;

    @Transactional(readOnly = true)
    public PageResponse<PostSummary> list(int page, int size, String keyword, PublishStatus status,
            Boolean hasUnpublishedChanges) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        QNote note = QNote.note;

        BooleanExpression predicate = note.kind.eq(NoteKind.POST);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            predicate = predicate.and(note.title.containsIgnoreCase(kw)
                    .or(note.summary.containsIgnoreCase(kw))
                    .or(note.content.containsIgnoreCase(kw)));
        }
        if (status != null) {
            predicate = predicate.and(note.publishStatus.eq(status));
        }

        List<Note> all = queryFactory.selectFrom(note)
                .where(predicate)
                .orderBy(note.updateTime.coalesce(note.createTime).desc())
                .fetch();
        Map<String, NoteRevision> revisions = revisionsById(all);
        Map<String, List<TagBrief>> tags = tagService.tagsByNoteIds(all.stream().map(Note::getId).toList());

        List<PostSummary> mapped = all.stream()
                .map(row -> toSummary(row,
                        row.getPublishedRevisionId() == null ? null
                                : revisions.get(row.getPublishedRevisionId()),
                        tags.getOrDefault(row.getId(), List.of())))
                .filter(row -> hasUnpublishedChanges == null
                        || row.hasUnpublishedChanges() == hasUnpublishedChanges)
                .toList();
        int from = Math.min((safePage - 1) * safeSize, mapped.size());
        int to = Math.min(from + safeSize, mapped.size());
        return new PageResponse<>(mapped.subList(from, to), mapped.size(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public PostDetail get(String id) {
        Note note = requirePost(id);
        return toDetail(note);
    }

    @Transactional
    public PostDetail create(CreatePostRequest request) {
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        String slug = normalizeSlug(request.slug());
        ensureSlugAvailable(slug, null);
        String coverId = attachmentService.validateCover(request.coverAttachmentId(), principal.userId());

        Note note = new Note();
        note.setKind(NoteKind.POST);
        note.setTitle(request.title().trim());
        note.setContent(request.content());
        note.setSummary(trimToNull(request.summary()));
        note.setSlug(slug);
        note.setCoverAttachmentId(coverId);
        note.setOwnerId(principal.userId());
        note.setPinned(shortFlag(request.pinned()));
        note.setPublishStatus(PublishStatus.DRAFT);
        note.setDraftModifiedAt(Instant.now());
        note.setCreateBy(principal.userId());
        noteRepository.saveAndFlush(note);

        tagService.replaceNoteTags(note.getId(),
                request.tags() == null ? List.of() : request.tags(), principal.userId());
        return toDetail(note);
    }

    @Transactional
    public PostDetail update(String id, UpdatePostRequest request) {
        Note note = requirePost(id);
        requireVersion(note, request.version());
        AuthPrincipal principal = SecurityUtils.requirePrincipal();

        boolean changed = false;
        String title = request.title().trim();
        if (!Objects.equals(note.getTitle(), title)) {
            note.setTitle(title);
            changed = true;
        }
        if (!Objects.equals(note.getContent(), request.content())) {
            note.setContent(request.content());
            changed = true;
        }

        if (request.summary() != null) {
            String summary = trimToNull(request.summary());
            if (note.getPublishedRevisionId() != null && summary == null) {
                throw ApiException.publishValidation("已发布文章的摘要不能清空");
            }
            if (!Objects.equals(note.getSummary(), summary)) {
                note.setSummary(summary);
                changed = true;
            }
        }

        if (request.slug() != null) {
            String slug = normalizeSlug(request.slug());
            if (note.getPublishedRevisionId() != null && !Objects.equals(note.getSlug(), slug)) {
                throw ApiException.invalidState("已发布文章的 slug 不可修改");
            }
            ensureSlugAvailable(slug, note.getId());
            if (!Objects.equals(note.getSlug(), slug)) {
                note.setSlug(slug);
                changed = true;
            }
        }

        String coverId = attachmentService.validateCover(request.coverAttachmentId(), note.getOwnerId());
        if (!Objects.equals(note.getCoverAttachmentId(), coverId)) {
            note.setCoverAttachmentId(coverId);
            changed = true;
        }

        if (request.pinned() != null) {
            note.setPinned(shortFlag(request.pinned()));
        }

        boolean tagsChanged = false;
        if (request.tags() != null) {
            tagsChanged = tagsChanged(note.getId(), request.tags());
            if (tagsChanged) {
                tagService.replaceNoteTags(note.getId(), request.tags(), principal.userId());
            }
        }

        if (changed || tagsChanged) {
            note.setDraftModifiedAt(Instant.now());
        }
        note.setUpdateBy(principal.userId());
        noteRepository.saveAndFlush(note);
        return toDetail(note);
    }

    /** 笔记转博客草稿：显式 promote，不允许反向。 */
    @Transactional
    public PostDetail promoteFromNote(String noteId) {
        Note note = noteRepository.findByIdAndKind(noteId, NoteKind.NOTE)
                .orElseThrow(() -> ApiException.notFound("笔记不存在"));
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        if (!principal.root() && !principal.userId().equals(note.getOwnerId())) {
            throw ApiException.notFound("笔记不存在");
        }
        note.setKind(NoteKind.POST);
        note.setFolderId(null);
        note.setPublishStatus(PublishStatus.DRAFT);
        note.setDraftModifiedAt(Instant.now());
        note.setUpdateBy(principal.userId());
        noteRepository.saveAndFlush(note);
        return toDetail(note);
    }

    @Transactional
    public PostDetail publish(String id, long version) {
        publishService.publish(id, version);
        return toDetail(requirePost(id));
    }

    @Transactional
    public PostDetail unpublish(String id) {
        publishService.unpublish(id);
        return toDetail(requirePost(id));
    }

    @Transactional
    public void delete(String id) {
        Note note = requirePost(id);
        if (note.getPublishStatus() == PublishStatus.PUBLISHED) {
            note.setPublishStatus(PublishStatus.UNPUBLISHED);
        }
        note.markDeleted(SecurityUtils.currentUserId());
        note.setUpdateBy(SecurityUtils.currentUserId());
        noteRepository.save(note);
    }

    @Transactional(readOnly = true)
    public Note requirePost(String id) {
        return noteRepository.findByIdAndKind(id, NoteKind.POST)
                .orElseThrow(() -> ApiException.notFound("文章不存在"));
    }

    public PostDetail toDetail(Note note) {
        NoteRevision revision = note.getPublishedRevisionId() == null ? null
                : revisionRepository.findById(note.getPublishedRevisionId()).orElse(null);
        return new PostDetail(
                note.getId(),
                note.getTitle(),
                note.getContent(),
                note.getSummary(),
                note.getSlug(),
                publicUrl(note),
                note.getCoverAttachmentId(),
                coverUrl(note.getCoverAttachmentId()),
                tagService.tagsOfNote(note.getId()),
                note.getPinned() == 1,
                note.getPublishStatus(),
                publishService.hasChanges(note, revision),
                note.getPublishedRevisionId(),
                revision == null ? null : revision.getRevisionNo(),
                revision == null ? null : revision.getTitle(),
                note.getFirstPublishedAt(),
                note.getLastPublishedAt(),
                note.getDraftModifiedAt(),
                note.getCreateTime(),
                note.getUpdateTime(),
                note.getVersion());
    }

    private PostSummary toSummary(Note note, NoteRevision revision, List<TagBrief> tags) {
        return new PostSummary(
                note.getId(),
                note.getTitle(),
                note.getSummary(),
                note.getSlug(),
                coverUrl(note.getCoverAttachmentId()),
                tags,
                note.getPinned() == 1,
                note.getPublishStatus(),
                publishService.hasChanges(note, revision),
                note.getFirstPublishedAt(),
                note.getLastPublishedAt(),
                note.getCreateTime(),
                note.getUpdateTime(),
                note.getVersion());
    }

    private Map<String, NoteRevision> revisionsById(List<Note> notes) {
        List<String> ids = notes.stream()
                .map(Note::getPublishedRevisionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return revisionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(NoteRevision::getId, Function.identity()));
    }

    private boolean tagsChanged(String noteId, List<String> requested) {
        List<String> current = tagService.tagsOfNote(noteId).stream()
                .map(TagBrief::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
        List<String> next = requested.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        return !new HashSet<>(current).equals(new HashSet<>(next));
    }

    private void ensureSlugAvailable(String slug, String noteId) {
        if (slug == null) {
            return;
        }
        if (slugService.isTaken(slug, noteId)) {
            throw ApiException.slugTaken("slug 已被其他文章使用");
        }
    }

    private String normalizeSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return slug.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String publicUrl(Note note) {
        return note.getSlug() == null ? null : "/posts/" + note.getSlug();
    }

    private String coverUrl(String attachmentId) {
        return attachmentId == null ? null : "/api/media/" + attachmentId;
    }

    private void requireVersion(Note note, Long requested) {
        if (requested == null || requested != note.getVersion()) {
            throw ApiException.versionConflict("文章已被修改，请刷新后重试");
        }
    }

    private short shortFlag(Boolean value) {
        return value != null && value ? (short) 1 : (short) 0;
    }
}
