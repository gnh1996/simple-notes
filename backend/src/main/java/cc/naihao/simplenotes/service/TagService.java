package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.NoteRevisionTag;
import cc.naihao.simplenotes.domain.NoteTag;
import cc.naihao.simplenotes.domain.Tag;
import cc.naihao.simplenotes.repository.NoteRevisionTagRepository;
import cc.naihao.simplenotes.repository.NoteTagRepository;
import cc.naihao.simplenotes.repository.TagRepository;
import cc.naihao.simplenotes.web.dto.TagBrief;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签解析与快照（docs/content-model.md §3.3）。
 *
 * <p>标签按名称大小写不敏感查找或创建；笔记 / 文章更新时全量替换当前标签，
 * 发布时再复制到 {@code note_revision_tag}。
 */
@Service
@RequiredArgsConstructor
public class TagService {

    private static final int MAX_TAGS = 10;
    private static final int MAX_TAG_LENGTH = 32;

    private final TagRepository tagRepository;
    private final NoteTagRepository noteTagRepository;
    private final NoteRevisionTagRepository noteRevisionTagRepository;

    /** 解析标签名：去空白、去重（大小写不敏感）、限制数量；不存在的标签自动创建。 */
    @Transactional
    public List<Tag> resolveTags(List<String> names, String operator) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            if (name.length() > MAX_TAG_LENGTH) {
                throw ApiException.badRequest("标签最长 " + MAX_TAG_LENGTH + " 个字符");
            }
            normalized.putIfAbsent(name.toLowerCase(), name);
        }
        if (normalized.size() > MAX_TAGS) {
            throw ApiException.badRequest("最多设置 " + MAX_TAGS + " 个标签");
        }

        List<Tag> result = new ArrayList<>();
        for (String name : normalized.values()) {
            Tag tag = tagRepository.findByNameIgnoreCase(name).orElseGet(() -> {
                Tag created = new Tag();
                created.setName(name);
                created.setCreateBy(operator);
                return tagRepository.save(created);
            });
            result.add(tag);
        }
        return result;
    }

    /** 全量替换某条内容的当前草稿标签。 */
    @Transactional
    public void replaceNoteTags(String noteId, List<String> names, String operator) {
        List<Tag> tags = resolveTags(names, operator);
        noteTagRepository.deleteAll(noteTagRepository.findByNoteId(noteId));
        noteTagRepository.flush();
        noteTagRepository.saveAll(tags.stream()
                .map(tag -> new NoteTag(noteId, tag.getId(), operator))
                .toList());
    }

    /** 发布快照标签：发布时从当前草稿标签复制。 */
    @Transactional
    public void snapshotTags(String noteId, String revisionId) {
        List<NoteTag> tags = noteTagRepository.findByNoteId(noteId);
        noteRevisionTagRepository.saveAll(tags.stream()
                .map(tag -> new NoteRevisionTag(revisionId, tag.getTagId()))
                .toList());
    }

    @Transactional(readOnly = true)
    public List<TagBrief> tagsOfNote(String noteId) {
        List<NoteTag> links = noteTagRepository.findByNoteId(noteId);
        return toBriefs(links.stream().map(NoteTag::getTagId).toList());
    }

    @Transactional(readOnly = true)
    public Map<String, List<TagBrief>> tagsByNoteIds(List<String> noteIds) {
        if (noteIds.isEmpty()) {
            return Map.of();
        }
        List<NoteTag> links = noteTagRepository.findByNoteIdIn(noteIds);
        Map<String, Tag> tags = tagsById(links.stream().map(NoteTag::getTagId).toList());
        return links.stream().collect(Collectors.groupingBy(NoteTag::getNoteId,
                Collectors.mapping(link -> toBrief(tags.get(link.getTagId())),
                        Collectors.filtering(brief -> brief != null, Collectors.toList()))));
    }

    @Transactional(readOnly = true)
    public List<TagBrief> tagsOfRevision(String revisionId) {
        List<NoteRevisionTag> links = noteRevisionTagRepository.findByRevisionId(revisionId);
        return toBriefs(links.stream().map(NoteRevisionTag::getTagId).toList());
    }

    @Transactional(readOnly = true)
    public Map<String, List<TagBrief>> tagsByRevisionIds(List<String> revisionIds) {
        if (revisionIds.isEmpty()) {
            return Map.of();
        }
        List<NoteRevisionTag> links = noteRevisionTagRepository.findByRevisionIdIn(revisionIds);
        Map<String, Tag> tags = tagsById(links.stream().map(NoteRevisionTag::getTagId).toList());
        return links.stream().collect(Collectors.groupingBy(NoteRevisionTag::getRevisionId,
                Collectors.mapping(link -> toBrief(tags.get(link.getTagId())),
                        Collectors.filtering(brief -> brief != null, Collectors.toList()))));
    }

    private List<TagBrief> toBriefs(List<String> tagIds) {
        Map<String, Tag> tags = tagsById(tagIds);
        return tagIds.stream()
                .map(tags::get)
                .filter(tag -> tag != null)
                .map(this::toBrief)
                .toList();
    }

    private Map<String, Tag> tagsById(List<String> tagIds) {
        if (tagIds.isEmpty()) {
            return Map.of();
        }
        return tagRepository.findAllById(tagIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
    }

    private TagBrief toBrief(Tag tag) {
        return tag == null ? null : new TagBrief(tag.getId(), tag.getName());
    }
}
