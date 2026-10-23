package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.Note;
import cc.naihao.simplenotes.domain.NoteFolder;
import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.NoteStatus;
import cc.naihao.simplenotes.domain.QNote;
import cc.naihao.simplenotes.domain.QNoteTag;
import cc.naihao.simplenotes.repository.NoteFolderRepository;
import cc.naihao.simplenotes.repository.NoteRepository;
import cc.naihao.simplenotes.security.AuthPrincipal;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.CreateNoteRequest;
import cc.naihao.simplenotes.web.dto.NoteDetail;
import cc.naihao.simplenotes.web.dto.NoteSummary;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.TagBrief;
import cc.naihao.simplenotes.web.dto.UpdateNoteRequest;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 随手记服务（docs/content-model.md §6.1）：只操作 {@code kind=NOTE}。
 *
 * <p>笔记按 owner 隔离；超管不受限。笔记没有发布状态，不会进入公开博客接口。
 */
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteRepository noteRepository;
    private final NoteFolderRepository folderRepository;
    private final NoteFolderService folderService;
    private final TagService tagService;
    private final JPAQueryFactory queryFactory;

    @Transactional(readOnly = true)
    public PageResponse<NoteSummary> list(int page, int size, String keyword, String folderId,
            String tagId, NoteStatus status, boolean pinnedOnly) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        QNote note = QNote.note;
        QNoteTag link = QNoteTag.noteTag;

        BooleanExpression predicate = note.kind.eq(NoteKind.NOTE);
        if (!principal.root()) {
            predicate = predicate.and(note.ownerId.eq(principal.userId()));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            predicate = predicate.and(note.title.containsIgnoreCase(kw)
                    .or(note.content.containsIgnoreCase(kw)));
        }
        if (folderId != null && !folderId.isBlank()) {
            predicate = predicate.and(note.folderId.eq(folderId));
        }
        if (tagId != null && !tagId.isBlank()) {
            predicate = predicate.and(JPAExpressions.selectOne()
                    .from(link)
                    .where(link.noteId.eq(note.id), link.tagId.eq(tagId))
                    .exists());
        }
        if (status != null) {
            predicate = predicate.and(note.status.eq(status));
        }
        if (pinnedOnly) {
            predicate = predicate.and(note.pinned.eq((short) 1));
        }

        List<Note> rows = queryFactory.selectFrom(note)
                .where(predicate)
                .orderBy(note.pinned.desc(), note.updateTime.coalesce(note.createTime).desc())
                .offset((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .fetch();
        Long total = queryFactory.select(note.count()).from(note).where(predicate).fetchOne();

        Map<String, List<TagBrief>> tags = tagService.tagsByNoteIds(
                rows.stream().map(Note::getId).toList());
        Map<String, NoteFolder> folders = foldersById(rows);
        List<NoteSummary> items = rows.stream()
                .map(row -> toSummary(row,
                        row.getFolderId() == null ? null : folders.get(row.getFolderId()),
                        tags.getOrDefault(row.getId(), List.of())))
                .toList();
        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public NoteDetail get(String id) {
        Note note = requireNote(id);
        requireAccess(note);
        return toDetail(note);
    }

    @Transactional
    public NoteDetail create(CreateNoteRequest request) {
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        NoteFolder folder = folderService.requireFolder(request.folderId(), principal.userId());

        Note note = new Note();
        note.setKind(NoteKind.NOTE);
        note.setTitle(request.title().trim());
        note.setContent(request.content());
        note.setOwnerId(principal.userId());
        note.setFolderId(folder == null ? null : folder.getId());
        note.setPinned(shortFlag(request.pinned()));
        note.setStatus(NoteStatus.ACTIVE);
        note.setCreateBy(principal.userId());
        noteRepository.saveAndFlush(note);

        tagService.replaceNoteTags(note.getId(),
                request.tags() == null ? List.of() : request.tags(), principal.userId());
        return toDetail(note);
    }

    @Transactional
    public NoteDetail update(String id, UpdateNoteRequest request) {
        Note note = requireNote(id);
        requireAccess(note);
        requireVersion(note.getVersion(), request.version());
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        NoteFolder folder = folderService.requireFolder(request.folderId(), note.getOwnerId());

        note.setTitle(request.title().trim());
        note.setContent(request.content());
        note.setFolderId(folder == null ? null : folder.getId());
        if (request.pinned() != null) {
            note.setPinned(shortFlag(request.pinned()));
        }
        if (request.status() != null) {
            note.setStatus(request.status());
        }
        note.setUpdateBy(principal.userId());
        // 即使只改标签，也让 note 行产生 update_time / version 变化
        note.setUpdateTime(Instant.now());
        noteRepository.saveAndFlush(note);

        if (request.tags() != null) {
            tagService.replaceNoteTags(note.getId(), request.tags(), principal.userId());
        }
        return toDetail(note);
    }

    @Transactional
    public void delete(String id) {
        Note note = requireNote(id);
        requireAccess(note);
        note.markDeleted(SecurityUtils.currentUserId());
        note.setUpdateBy(SecurityUtils.currentUserId());
        noteRepository.save(note);
    }

    @Transactional(readOnly = true)
    public Note requireNote(String id) {
        return noteRepository.findByIdAndKind(id, NoteKind.NOTE)
                .orElseThrow(() -> ApiException.notFound("笔记不存在"));
    }

    public void requireAccess(Note note) {
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        if (!principal.root() && !principal.userId().equals(note.getOwnerId())) {
            throw ApiException.notFound("笔记不存在");
        }
    }

    private NoteDetail toDetail(Note note) {
        NoteFolder folder = note.getFolderId() == null ? null
                : folderRepository.findById(note.getFolderId()).orElse(null);
        return new NoteDetail(
                note.getId(),
                note.getTitle(),
                note.getContent(),
                note.getFolderId(),
                folder == null ? null : folder.getName(),
                tagService.tagsOfNote(note.getId()),
                note.getPinned() == 1,
                note.getStatus(),
                note.getCreateTime(),
                note.getUpdateTime(),
                note.getVersion());
    }

    private NoteSummary toSummary(Note note, NoteFolder folder, List<TagBrief> tags) {
        return new NoteSummary(
                note.getId(),
                note.getTitle(),
                note.getFolderId(),
                folder == null ? null : folder.getName(),
                tags,
                note.getPinned() == 1,
                note.getStatus(),
                note.getCreateTime(),
                note.getUpdateTime(),
                note.getVersion());
    }

    private Map<String, NoteFolder> foldersById(List<Note> notes) {
        List<String> ids = notes.stream()
                .map(Note::getFolderId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return folderRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(NoteFolder::getId, Function.identity()));
    }

    private void requireVersion(long current, Long requested) {
        if (requested == null || requested != current) {
            throw ApiException.versionConflict("内容已被修改，请刷新后重试");
        }
    }

    private short shortFlag(Boolean value) {
        return value != null && value ? (short) 1 : (short) 0;
    }
}
