package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.Note;
import cc.naihao.simplenotes.domain.NoteFolder;
import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.repository.NoteFolderRepository;
import cc.naihao.simplenotes.repository.NoteRepository;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.NoteFolderRequest;
import cc.naihao.simplenotes.web.dto.NoteFolderResponse;
import java.util.List;
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

    @Transactional(readOnly = true)
    public List<NoteFolderResponse> list() {
        String userId = SecurityUtils.requirePrincipal().userId();
        return folderRepository.findByOwnerIdOrderBySortAscCreateTimeDesc(userId).stream()
                .map(this::toResponse)
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
        return toResponse(folder);
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
        return toResponse(folder);
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

    private NoteFolderResponse toResponse(NoteFolder folder) {
        return new NoteFolderResponse(folder.getId(), folder.getName(), folder.getSort(), folder.getCreateTime());
    }
}
