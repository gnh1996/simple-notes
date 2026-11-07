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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记事本文件夹（docs/content-model.md §3.4）：v2.0 树形、按所有者隔离。
 *
 * <p>同父级下名称唯一、跨层级可同名；最多 5 级；有子文件夹时禁止删除。
 */
@Service
@RequiredArgsConstructor
public class NoteFolderService {

    private static final int MAX_DEPTH = 5;

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
        Map<String, Long> direct = noteCounts(folders.stream().map(NoteFolder::getId).toList());
        Map<String, Long> subtree = subtreeCounts(folders, direct);
        return folders.stream()
                .map(folder -> toResponse(folder, subtree.getOrDefault(folder.getId(), 0L)))
                .toList();
    }

    @Transactional
    public NoteFolderResponse create(NoteFolderRequest request) {
        String userId = SecurityUtils.requirePrincipal().userId();
        String name = request.name().trim();
        NoteFolder parent = resolveParent(request.parentId(), userId);
        if (parent != null && depthOf(parent) + 1 > MAX_DEPTH) {
            throw ApiException.badRequest("文件夹最多支持 5 级");
        }
        String parentId = parent == null ? null : parent.getId();
        if (existsName(userId, parentId, name, null)) {
            throw ApiException.conflict("文件夹名称已存在");
        }
        NoteFolder folder = new NoteFolder();
        folder.setOwnerId(userId);
        folder.setParentId(parentId);
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
        // v2.0 不支持移动文件夹：request.parentId() 在编辑时忽略。
        if (existsName(userId, folder.getParentId(), name, id)) {
            throw ApiException.conflict("文件夹名称已存在");
        }
        folder.setName(name);
        if (request.sort() != null) {
            folder.setSort(request.sort());
        }
        folder.setUpdateBy(userId);
        folderRepository.save(folder);
        return toResponse(folder, subtreeCount(folder, userId));
    }

    /** 删除文件夹不删除笔记：有子文件夹时拒绝；叶子文件夹的笔记移到未分类。 */
    @Transactional
    public void delete(String id) {
        String userId = SecurityUtils.requirePrincipal().userId();
        NoteFolder folder = requireOwned(id, userId);
        if (folderRepository.existsByOwnerIdAndParentId(userId, folder.getId())) {
            throw ApiException.conflict("请先删除子文件夹");
        }
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

    /**
     * 返回文件夹自身 + 全部子孙 id，用于列表按子树筛选。
     * 文件夹不存在或不属于该用户时返回空列表。
     */
    @Transactional(readOnly = true)
    public List<String> subtreeIds(String folderId, String ownerId) {
        List<NoteFolder> folders = folderRepository.findByOwnerIdOrderBySortAscCreateTimeDesc(ownerId);
        boolean exists = folders.stream().anyMatch(folder -> folder.getId().equals(folderId));
        if (!exists) {
            return List.of();
        }
        Map<String, List<String>> children = new HashMap<>();
        for (NoteFolder folder : folders) {
            String parentId = folder.getParentId() == null ? "" : folder.getParentId();
            children.computeIfAbsent(parentId, key -> new ArrayList<>()).add(folder.getId());
        }
        List<String> result = new ArrayList<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(folderId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);
            queue.addAll(children.getOrDefault(current, List.of()));
        }
        return result;
    }

    private NoteFolder resolveParent(String parentId, String userId) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        return folderRepository.findByIdAndOwnerId(parentId, userId)
                .orElseThrow(() -> ApiException.badRequest("父文件夹不存在"));
    }

    private int depthOf(NoteFolder folder) {
        int depth = 1;
        String parentId = folder.getParentId();
        int guard = 0;
        while (parentId != null && !parentId.isBlank() && guard++ < MAX_DEPTH) {
            NoteFolder parent = folderRepository.findByIdAndOwnerId(parentId, folder.getOwnerId()).orElse(null);
            if (parent == null) {
                break;
            }
            depth++;
            parentId = parent.getParentId();
        }
        return depth;
    }

    private boolean existsName(String userId, String parentId, String name, String excludeId) {
        if (parentId == null || parentId.isBlank()) {
            return excludeId == null
                    ? folderRepository.existsByOwnerIdAndParentIdIsNullAndNameIgnoreCase(userId, name)
                    : folderRepository.existsByOwnerIdAndParentIdIsNullAndNameIgnoreCaseAndIdNot(userId, name, excludeId);
        }
        return excludeId == null
                ? folderRepository.existsByOwnerIdAndParentIdAndNameIgnoreCase(userId, parentId, name)
                : folderRepository.existsByOwnerIdAndParentIdAndNameIgnoreCaseAndIdNot(userId, parentId, name,
                        excludeId);
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

    /** 子树计数：每个文件夹累加自身及全部子孙的 direct 数量（沿子链向下，与 subtreeIds 同构）。 */
    private Map<String, Long> subtreeCounts(List<NoteFolder> folders, Map<String, Long> direct) {
        Map<String, List<String>> children = new HashMap<>();
        for (NoteFolder folder : folders) {
            String parentId = folder.getParentId() == null ? "" : folder.getParentId();
            children.computeIfAbsent(parentId, key -> new ArrayList<>()).add(folder.getId());
        }
        Map<String, Long> result = new HashMap<>();
        for (NoteFolder folder : folders) {
            long sum = 0;
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(folder.getId());
            while (!queue.isEmpty()) {
                String current = queue.poll();
                sum += direct.getOrDefault(current, 0L);
                queue.addAll(children.getOrDefault(current, List.of()));
            }
            result.put(folder.getId(), sum);
        }
        return result;
    }

    private long subtreeCount(NoteFolder folder, String userId) {
        List<NoteFolder> folders = folderRepository.findByOwnerIdOrderBySortAscCreateTimeDesc(userId);
        Map<String, Long> direct = noteCounts(folders.stream().map(NoteFolder::getId).toList());
        return subtreeCounts(folders, direct).getOrDefault(folder.getId(), 0L);
    }

    private NoteFolderResponse toResponse(NoteFolder folder, long noteCount) {
        return new NoteFolderResponse(folder.getId(), folder.getParentId(), folder.getName(), folder.getSort(),
                noteCount, folder.getCreateTime(), folder.getUpdateTime());
    }
}
