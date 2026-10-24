package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.Note;
import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.NoteRevision;
import cc.naihao.simplenotes.domain.PublishStatus;
import cc.naihao.simplenotes.domain.QNote;
import cc.naihao.simplenotes.domain.QNoteRevision;
import cc.naihao.simplenotes.domain.QNoteRevisionTag;
import cc.naihao.simplenotes.domain.QTag;
import cc.naihao.simplenotes.domain.SysUser;
import cc.naihao.simplenotes.repository.NoteRepository;
import cc.naihao.simplenotes.repository.NoteRevisionRepository;
import cc.naihao.simplenotes.repository.SysUserRepository;
import cc.naihao.simplenotes.web.dto.ArchiveResponse;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.PublishedPostDetail;
import cc.naihao.simplenotes.web.dto.PublishedPostSummary;
import cc.naihao.simplenotes.web.dto.TagBrief;
import cc.naihao.simplenotes.web.dto.TagCountResponse;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公开博客读取（docs/content-model.md §5）：只读已发布快照，绝不返回草稿 / 笔记。
 */
@Service
@RequiredArgsConstructor
public class BlogService {

    private final NoteRepository noteRepository;
    private final NoteRevisionRepository revisionRepository;
    private final SysUserRepository userRepository;
    private final TagService tagService;
    private final JPAQueryFactory queryFactory;

    @Transactional(readOnly = true)
    public PageResponse<PublishedPostSummary> list(int page, int size, String tagId, Integer year,
            Integer month) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        if (month != null && (month < 1 || month > 12)) {
            throw ApiException.badRequest("月份需在 1-12 之间");
        }
        QNote note = QNote.note;
        QNoteRevision revision = QNoteRevision.noteRevision;
        QNoteRevisionTag revisionTag = QNoteRevisionTag.noteRevisionTag;

        BooleanExpression predicate = publicPredicate(note, revision);
        if (tagId != null && !tagId.isBlank()) {
            predicate = predicate.and(JPAExpressions.selectOne()
                    .from(revisionTag)
                    .where(revisionTag.revisionId.eq(revision.id), revisionTag.tagId.eq(tagId))
                    .exists());
        }
        if (year != null) {
            LocalDate startDate = LocalDate.of(year, month == null ? 1 : month, 1);
            Instant start = startDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = startDate.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            predicate = predicate.and(note.firstPublishedAt.goe(start)
                    .and(note.firstPublishedAt.lt(end)));
        }

        List<Note> rows = queryFactory.select(note)
                .from(note, revision)
                .where(predicate)
                .orderBy(note.pinned.desc(), note.firstPublishedAt.desc())
                .offset((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .fetch();
        Long total = queryFactory.select(note.count()).from(note, revision).where(predicate).fetchOne();

        Map<String, NoteRevision> revisions = revisions(rows);
        Map<String, List<TagBrief>> tags = tagService.tagsByRevisionIds(
                rows.stream().map(Note::getPublishedRevisionId).filter(Objects::nonNull).toList());
        List<PublishedPostSummary> items = rows.stream()
                .map(row -> toSummary(row, revisions.get(row.getPublishedRevisionId()),
                        tags.getOrDefault(row.getPublishedRevisionId(), List.of())))
                .filter(Objects::nonNull)
                .toList();
        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public PublishedPostDetail detail(String slug) {
        Note note = noteRepository.findByKindAndSlugAndPublishStatus(NoteKind.POST, slug,
                        PublishStatus.PUBLISHED)
                .orElseThrow(() -> ApiException.notFound("文章不存在"));
        NoteRevision revision = note.getPublishedRevisionId() == null ? null
                : revisionRepository.findById(note.getPublishedRevisionId()).orElse(null);
        if (revision == null) {
            throw ApiException.notFound("文章不存在");
        }
        String authorName = userRepository.findById(note.getOwnerId())
                .map(SysUser::getNickname)
                .orElse("作者");
        return new PublishedPostDetail(
                note.getSlug(),
                revision.getTitle(),
                revision.getContent(),
                revision.getSummary(),
                coverUrl(revision.getCoverAttachmentId()),
                tagService.tagsOfRevision(revision.getId()),
                authorName,
                note.getFirstPublishedAt(),
                note.getLastPublishedAt(),
                readingMinutes(revision.getContent()));
    }

    @Transactional(readOnly = true)
    public List<TagCountResponse> tags() {
        QNote note = QNote.note;
        QNoteRevision revision = QNoteRevision.noteRevision;
        QNoteRevisionTag revisionTag = QNoteRevisionTag.noteRevisionTag;
        QTag tag = QTag.tag;

        List<Tuple> rows = queryFactory
                .select(tag.id, tag.name, note.id.count())
                .from(note, revision, revisionTag, tag)
                .where(publicPredicate(note, revision),
                        revisionTag.revisionId.eq(revision.id),
                        tag.id.eq(revisionTag.tagId))
                .groupBy(tag.id, tag.name)
                .orderBy(note.id.count().desc(), tag.name.asc())
                .fetch();
        return rows.stream()
                .map(row -> new TagCountResponse(row.get(tag.id), row.get(tag.name),
                        row.get(2, Long.class) == null ? 0L : row.get(2, Long.class)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ArchiveResponse> archives() {
        QNote note = QNote.note;
        List<Instant> published = queryFactory.select(note.firstPublishedAt)
                .from(note)
                .where(note.kind.eq(NoteKind.POST),
                        note.publishStatus.eq(PublishStatus.PUBLISHED),
                        note.deleteFlag.eq((short) 0),
                        note.firstPublishedAt.isNotNull())
                .fetch();
        Map<YearMonth, Long> counts = published.stream()
                .filter(Objects::nonNull)
                .map(instant -> YearMonth.from(instant.atZone(ZoneOffset.UTC)))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<YearMonth, Long>comparingByKey().reversed())
                .map(entry -> new ArchiveResponse(entry.getKey().getYear(), entry.getKey().getMonthValue(),
                        entry.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<PublishedPostSummary> search(int page, int size, String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        QNote note = QNote.note;
        QNoteRevision revision = QNoteRevision.noteRevision;
        BooleanExpression predicate = publicPredicate(note, revision);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            predicate = predicate.and(revision.title.containsIgnoreCase(kw)
                    .or(revision.content.containsIgnoreCase(kw)));
        }

        List<Note> rows = queryFactory.select(note)
                .from(note, revision)
                .where(predicate)
                .orderBy(note.pinned.desc(), note.firstPublishedAt.desc())
                .offset((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .fetch();
        Long total = queryFactory.select(note.count()).from(note, revision).where(predicate).fetchOne();
        Map<String, NoteRevision> revisions = revisions(rows);
        Map<String, List<TagBrief>> tags = tagService.tagsByRevisionIds(
                rows.stream().map(Note::getPublishedRevisionId).filter(Objects::nonNull).toList());
        List<PublishedPostSummary> items = rows.stream()
                .map(row -> toSummary(row, revisions.get(row.getPublishedRevisionId()),
                        tags.getOrDefault(row.getPublishedRevisionId(), List.of())))
                .filter(Objects::nonNull)
                .toList();
        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    private BooleanExpression publicPredicate(QNote note, QNoteRevision revision) {
        return note.kind.eq(NoteKind.POST)
                .and(note.publishStatus.eq(PublishStatus.PUBLISHED))
                .and(note.deleteFlag.eq((short) 0))
                .and(note.publishedRevisionId.isNotNull())
                .and(revision.id.eq(note.publishedRevisionId));
    }

    private Map<String, NoteRevision> revisions(List<Note> notes) {
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

    private PublishedPostSummary toSummary(Note note, NoteRevision revision,
            List<TagBrief> tags) {
        if (revision == null) {
            return null;
        }
        return new PublishedPostSummary(
                note.getSlug(),
                revision.getTitle(),
                revision.getSummary(),
                coverUrl(revision.getCoverAttachmentId()),
                tags,
                note.getPinned() == 1,
                note.getFirstPublishedAt(),
                readingMinutes(revision.getContent()));
    }

    /** 阅读时长：非空白字符数 / 400，至少 1 分钟（与前端公式一致） */
    private int readingMinutes(String content) {
        if (content == null || content.isBlank()) {
            return 1;
        }
        int chars = content.replaceAll("\\s+", "").length();
        return Math.max(1, (int) Math.round(chars / 400.0));
    }

    private String coverUrl(String attachmentId) {
        return attachmentId == null ? null : "/api/media/" + attachmentId;
    }
}
