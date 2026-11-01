package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.Note;
import cc.naihao.simplenotes.domain.NoteFolder;
import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.QNote;
import cc.naihao.simplenotes.repository.NoteFolderRepository;
import cc.naihao.simplenotes.repository.NoteRepository;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.NoteFolderRequest;
import cc.naihao.simplenotes.web.dto.NoteFolderResponse;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记事本文件夹（docs/content-model.md §3.4）：v1 单层、按所有者隔离。
 */
@Service
@RequiredArgsConstructor
public class NoteFolderService {

    private final NoteFolderRepository folderRepository;
    private final NoteRepository noteRepository;
    private final JPAQueryFactory queryFactory;

    @Transactional(readOnly = true)
    public List<NoteFolderResponse> list() {
        String userId = SecurityUtils.requirePrincipal().userId();
        List<NoteFolder> folders = folderRepository.findByOwnerIdOrderBySortAscCreateTimeDesc(userId);
        if (folders.isEmpty()) {
            return List.of();
        }
        Map<String, Long> counts = noteCounts(
                folders.stream().map(NoteFolder::getId).toList());
        return folders.stream()
                .map(folder -> toResponse(folder, counts.getOrDefault(folder.getId(), 0L)))
                .toList();
    }

    @Transactional
    public NoteFolderResponse create(NoteFolderRequest request) {
        String userId = SecurityUtils.requirePrincipal().userId();
        String name = request.name().trim();
        if (folderRepository.existsByOwnerIdAndNameIgnoreCase(userId, name)) {
            throw ApiException.conflict("文件夹名称已存在");
        }
        NoteFolder folder = new NoteFolder();
        folder.setOwnerId(userId);
        folder.setName(name);
        folder.setSort(request.sort() == null ? 0 : request.sort());
        folder.setCreateBy(userId);
        folderRepository.save(folder);
        return toResponse(folder, 0L);
    }

    @Transactional
    public NoteFolderResponse update(String id, NoteFolderRequest request) {
        String userId = SecurityUtils.requirePrincipal().userId();
        NoteFolder folder = requireOwned(id, userId);
        String name = request.name().trim();
        if (folderRepository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(userId, name, id)) {
            throw ApiException.conflict("文件夹名称已存在");
        }
        folder.setName(name);
        if (request.sort() != null) {
            folder.setSort(request.sort());
        }
        folder.setUpdateBy(userId);
        folderRepository.save(folder);
        return toResponse(folder, noteRepository.countByFolderIdAndKind(folder.getId(), NoteKind.NOTE));
    }

    /** 删除文件夹不删除笔记：先把该文件夹下的笔记移到未分类。 */
    @Transactional
    public void delete(String id) {
        String userId = SecurityUtils.requirePrincipal().userId();
        NoteFolder folder = requireOwned(id, userId);
        List<Note> notes = noteRepository.findByFolderIdAndKind(folder.getId(), NoteKind.NOTE);
        notes.forEach(note -> {
            note.setFolderId(null);
            note.setUpdateBy(userId);
        });
        noteRepository.saveAll(notes);
        folder.markDeleted(userId);
        folderRepository.save(folder);
    }

    @Transactional(readOnly = true)
    public NoteFolder requireFolder(String folderId, String ownerId) {
        if (folderId == null || folderId.isBlank()) {
            return null;
        }
        return folderRepository.findByIdAndOwnerId(folderId, ownerId)
                .orElseThrow(() -> ApiException.badRequest("文件夹不存在"));
    }

    private NoteFolder requireOwned(String id, String userId) {
        return folderRepository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> ApiException.notFound("文件夹不存在"));
    }

    /** 按 folder_id 聚合未删除随手记数量；一次查询覆盖整个列表。 */
    private Map<String, Long> noteCounts(List<String> folderIds) {
        QNote note = QNote.note;
        return queryFactory.select(note.folderId, note.count())
                .from(note)
                .where(note.kind.eq(NoteKind.NOTE), note.folderId.in(folderIds))
                .groupBy(note.folderId)
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        tuple -> tuple.get(note.folderId),
                        tuple -> tuple.get(note.count())));
    }

    private NoteFolderResponse toResponse(NoteFolder folder, long noteCount) {
        return new NoteFolderResponse(folder.getId(), folder.getName(), folder.getSort(), noteCount,
                folder.getCreateTime(), folder.getUpdateTime());
    }
}
