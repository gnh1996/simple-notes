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
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.DateTimeExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公开博客读取（docs/content-model.md §5 / §6.4）：只读已发布快照。
 *
 * <p>列表、搜索、归档与详情上下篇共用同一套排序参数：
 * {@code pinnedFirst} + {@code direction} + {@code sortBy}。
 */
@Service
@RequiredArgsConstructor
public class BlogService {

    private final NoteRepository noteRepository;
    private final NoteRevisionRepository revisionRepository;
    private final SysUserRepository userRepository;
    private final TagService tagService;
    private final MediaUrlResolver mediaUrlResolver;
    private final JPAQueryFactory queryFactory;

    @Transactional(readOnly = true)
    public PageResponse<PublishedPostSummary> list(int page, int size, String tagId, Integer year,
            Integer month, boolean pinnedFirst, BlogSortDirection direction, BlogSortField sortBy) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        validatePeriod(year, month);

        QNote note = QNote.note;
        QNoteRevision revision = QNoteRevision.noteRevision;
        BooleanExpression predicate = publicPredicate(note, revision);
        predicate = applyTag(predicate, tagId, revision);
        predicate = applyPeriod(predicate, note, year, month, sortBy);

        List<Note> rows = queryFactory.select(note)
                .from(note, revision)
                .where(predicate)
                .orderBy(orderSpecifiers(note, sortBy, direction, pinnedFirst))
                .offset((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .fetch();
        Long total = queryFactory.select(note.count()).from(note, revision).where(predicate).fetchOne();

        return new PageResponse<>(toSummaries(rows), total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public PublishedPostDetail detail(String slug, boolean pinnedFirst, BlogSortDirection direction,
            BlogSortField sortBy, String tagId, Integer year, Integer month, String keyword) {
        validatePeriod(year, month);
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

        QNote qNote = QNote.note;
        QNoteRevision qRevision = QNoteRevision.noteRevision;
        BooleanExpression base = publicPredicate(qNote, qRevision);
        BooleanExpression filtered = applyTag(base, tagId, qRevision);
        filtered = applyPeriod(filtered, qNote, year, month, sortBy);
        filtered = applyKeyword(filtered, keyword, qRevision);
        // 当前文章不在筛选集合内（手动改 tagId / q 等）时忽略筛选，保证详情仍可用。
        if (!matches(filtered, note.getId())) {
            filtered = base;
        }

        Note prev = findNeighbor(note, filtered, sortBy, direction, pinnedFirst, true);
        Note next = findNeighbor(note, filtered, sortBy, direction, pinnedFirst, false);
        List<Note> neighbors = new ArrayList<>();
        if (prev != null) {
            neighbors.add(prev);
        }
        if (next != null) {
            neighbors.add(next);
        }
        Map<String, NoteRevision> neighborRevisions = revisions(neighbors);
        Map<String, List<TagBrief>> neighborTags = tagService.tagsByRevisionIds(
                neighbors.stream().map(Note::getPublishedRevisionId).filter(Objects::nonNull).toList());

        return new PublishedPostDetail(
                note.getSlug(),
                revision.getTitle(),
                revision.getContent(),
                revision.getSummary(),
                coverUrl(revision.getCoverAttachmentId()),
                tagService.tagsOfRevision(revision.getId()),
                note.getPinned() == 1,
                authorName,
                note.getFirstPublishedAt(),
                note.getLastPublishedAt(),
                note.getAuthoredAt(),
                displayAt(note),
                readingMinutes(revision.getContent()),
                neighborSummary(prev, neighborRevisions, neighborTags),
                neighborSummary(next, neighborRevisions, neighborTags));
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
    public List<ArchiveResponse> archives(BlogSortField sortBy, BlogSortDirection direction) {
        QNote note = QNote.note;
        QNoteRevision revision = QNoteRevision.noteRevision;
        List<Instant> values = queryFactory.select(timeExpression(note, sortBy))
                .from(note, revision)
                .where(publicPredicate(note, revision))
                .fetch();

        Map<YearMonth, Long> counts = values.stream()
                .filter(Objects::nonNull)
                .map(instant -> YearMonth.from(instant.atZone(ZoneOffset.UTC)))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        Comparator<Map.Entry<YearMonth, Long>> comparator = Map.Entry.comparingByKey();
        if (!direction.ascending()) {
            comparator = comparator.reversed();
        }
        return counts.entrySet().stream()
                .sorted(comparator)
                .map(entry -> new ArchiveResponse(entry.getKey().getYear(), entry.getKey().getMonthValue(),
                        entry.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<PublishedPostSummary> search(int page, int size, String keyword,
            boolean pinnedFirst, BlogSortDirection direction, BlogSortField sortBy) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        QNote note = QNote.note;
        QNoteRevision revision = QNoteRevision.noteRevision;
        BooleanExpression predicate = applyKeyword(publicPredicate(note, revision), keyword, revision);

        List<Note> rows = queryFactory.select(note)
                .from(note, revision)
                .where(predicate)
                .orderBy(orderSpecifiers(note, sortBy, direction, pinnedFirst))
                .offset((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .fetch();
        Long total = queryFactory.select(note.count()).from(note, revision).where(predicate).fetchOne();

        return new PageResponse<>(toSummaries(rows), total == null ? 0 : total, safePage, safeSize);
    }

    private BooleanExpression publicPredicate(QNote note, QNoteRevision revision) {
        return note.kind.eq(NoteKind.POST)
                .and(note.publishStatus.eq(PublishStatus.PUBLISHED))
                .and(note.deleteFlag.eq((short) 0))
                .and(note.publishedRevisionId.isNotNull())
                .and(revision.id.eq(note.publishedRevisionId));
    }

    private BooleanExpression applyTag(BooleanExpression predicate, String tagId,
            QNoteRevision revision) {
        if (tagId == null || tagId.isBlank()) {
            return predicate;
        }
        QNoteRevisionTag revisionTag = QNoteRevisionTag.noteRevisionTag;
        return predicate.and(JPAExpressions.selectOne()
                .from(revisionTag)
                .where(revisionTag.revisionId.eq(revision.id), revisionTag.tagId.eq(tagId))
                .exists());
    }

    private BooleanExpression applyPeriod(BooleanExpression predicate, QNote note, Integer year,
            Integer month, BlogSortField sortBy) {
        if (year == null) {
            return predicate;
        }
        LocalDate startDate = LocalDate.of(year, month == null ? 1 : month, 1);
        LocalDate endDate = month == null ? startDate.plusYears(1) : startDate.plusMonths(1);
        Instant start = startDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = endDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        DateTimeExpression<Instant> time = timeExpression(note, sortBy);
        return predicate.and(time.goe(start).and(time.lt(end)));
    }

    private BooleanExpression applyKeyword(BooleanExpression predicate, String keyword,
            QNoteRevision revision) {
        if (keyword == null || keyword.isBlank()) {
            return predicate;
        }
        String kw = keyword.trim();
        return predicate.and(revision.title.containsIgnoreCase(kw)
                .or(revision.content.containsIgnoreCase(kw)));
    }

    private boolean matches(BooleanExpression predicate, String noteId) {
        QNote note = QNote.note;
        QNoteRevision revision = QNoteRevision.noteRevision;
        Long count = queryFactory.select(note.count())
                .from(note, revision)
                .where(predicate, note.id.eq(noteId))
                .fetchOne();
        return count != null && count > 0;
    }

    private Note findNeighbor(Note current, BooleanExpression predicate, BlogSortField sortBy,
            BlogSortDirection direction, boolean pinnedFirst, boolean before) {
        QNote note = QNote.note;
        QNoteRevision revision = QNoteRevision.noteRevision;
        BooleanExpression comparison = neighborPredicate(note, current, sortBy, direction, pinnedFirst,
                before);
        OrderSpecifier<?>[] orders = before
                ? reversedOrderSpecifiers(note, sortBy, direction, pinnedFirst)
                : orderSpecifiers(note, sortBy, direction, pinnedFirst);
        return queryFactory.select(note)
                .from(note, revision)
                .where(predicate, comparison)
                .orderBy(orders)
                .limit(1)
                .fetchOne();
    }

    private BooleanExpression neighborPredicate(QNote note, Note current, BlogSortField sortBy,
            BlogSortDirection direction, boolean pinnedFirst, boolean before) {
        DateTimeExpression<Instant> time = timeExpression(note, sortBy);
        Instant currentTime = timeValue(current, sortBy);
        BooleanExpression timeComparison = before
                ? timeBefore(time, current, currentTime, direction)
                : timeAfter(time, current, currentTime, direction);
        if (!pinnedFirst) {
            return timeComparison;
        }
        BooleanExpression samePinned = note.pinned.eq(current.getPinned());
        return before
                ? note.pinned.gt(current.getPinned()).or(samePinned.and(timeComparison))
                : note.pinned.lt(current.getPinned()).or(samePinned.and(timeComparison));
    }

    private BooleanExpression timeBefore(DateTimeExpression<Instant> time, Note current,
            Instant currentTime, BlogSortDirection direction) {
        if (direction.ascending()) {
            return time.lt(currentTime)
                    .or(time.eq(currentTime).and(QNote.note.id.lt(current.getId())));
        }
        return time.gt(currentTime)
                .or(time.eq(currentTime).and(QNote.note.id.gt(current.getId())));
    }

    private BooleanExpression timeAfter(DateTimeExpression<Instant> time, Note current,
            Instant currentTime, BlogSortDirection direction) {
        if (direction.ascending()) {
            return time.gt(currentTime)
                    .or(time.eq(currentTime).and(QNote.note.id.gt(current.getId())));
        }
        return time.lt(currentTime)
                .or(time.eq(currentTime).and(QNote.note.id.lt(current.getId())));
    }

    private OrderSpecifier<?>[] orderSpecifiers(QNote note, BlogSortField sortBy,
            BlogSortDirection direction, boolean pinnedFirst) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        if (pinnedFirst) {
            orders.add(note.pinned.desc());
        }
        DateTimeExpression<Instant> time = timeExpression(note, sortBy);
        orders.add(direction.ascending() ? time.asc() : time.desc());
        orders.add(direction.ascending() ? note.id.asc() : note.id.desc());
        return orders.toArray(OrderSpecifier[]::new);
    }

    private OrderSpecifier<?>[] reversedOrderSpecifiers(QNote note, BlogSortField sortBy,
            BlogSortDirection direction, boolean pinnedFirst) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        if (pinnedFirst) {
            orders.add(note.pinned.asc());
        }
        DateTimeExpression<Instant> time = timeExpression(note, sortBy);
        orders.add(direction.ascending() ? time.desc() : time.asc());
        orders.add(direction.ascending() ? note.id.desc() : note.id.asc());
        return orders.toArray(OrderSpecifier[]::new);
    }

    private DateTimeExpression<Instant> timeExpression(QNote note, BlogSortField sortBy) {
        return switch (sortBy) {
            case DISPLAY_AT -> note.authoredAt.coalesce(note.firstPublishedAt);
            case FIRST_PUBLISHED_AT -> note.firstPublishedAt;
            case LAST_PUBLISHED_AT -> note.lastPublishedAt;
        };
    }

    private Instant timeValue(Note note, BlogSortField sortBy) {
        return switch (sortBy) {
            case DISPLAY_AT -> displayAt(note);
            case FIRST_PUBLISHED_AT -> note.getFirstPublishedAt();
            case LAST_PUBLISHED_AT -> note.getLastPublishedAt();
        };
    }

    private Instant displayAt(Note note) {
        return note.getAuthoredAt() != null ? note.getAuthoredAt() : note.getFirstPublishedAt();
    }

    private void validatePeriod(Integer year, Integer month) {
        if (month != null && (month < 1 || month > 12)) {
            throw ApiException.badRequest("月份需在 1-12 之间");
        }
        if (year != null && (year < 1 || year > 9999)) {
            throw ApiException.badRequest("年份需在 1-9999 之间");
        }
    }

    private List<PublishedPostSummary> toSummaries(List<Note> notes) {
        Map<String, NoteRevision> revisions = revisions(notes);
        Map<String, List<TagBrief>> tags = tagService.tagsByRevisionIds(
                notes.stream().map(Note::getPublishedRevisionId).filter(Objects::nonNull).toList());
        return notes.stream()
                .map(note -> toSummary(note, revisions.get(note.getPublishedRevisionId()),
                        tags.getOrDefault(note.getPublishedRevisionId(), List.of())))
                .filter(Objects::nonNull)
                .toList();
    }

    private PublishedPostSummary neighborSummary(Note note, Map<String, NoteRevision> revisions,
            Map<String, List<TagBrief>> tags) {
        if (note == null) {
            return null;
        }
        return toSummary(note, revisions.get(note.getPublishedRevisionId()),
                tags.getOrDefault(note.getPublishedRevisionId(), List.of()));
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
                note.getLastPublishedAt(),
                note.getAuthoredAt(),
                displayAt(note),
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
        return attachmentId == null ? null : mediaUrlResolver.mediaPath(attachmentId);
    }
}
