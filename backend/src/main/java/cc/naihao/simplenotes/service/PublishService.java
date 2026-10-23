package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.Note;
import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.NoteRevision;
import cc.naihao.simplenotes.domain.NoteStatus;
import cc.naihao.simplenotes.domain.PublishStatus;
import cc.naihao.simplenotes.repository.NoteRepository;
import cc.naihao.simplenotes.repository.NoteRevisionRepository;
import cc.naihao.simplenotes.security.SecurityUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布 / 更新发布 / 下架（docs/content-model.md §4）。
 *
 * <p>发布 = 把当前草稿复制为不可变 {@code note_revision}，并移动 note 上的公开快照指针；
 * 普通保存不会影响线上内容。
 */
@Service
@RequiredArgsConstructor
public class PublishService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final DateTimeFormatter SLUG_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final NoteRepository noteRepository;
    private final NoteRevisionRepository revisionRepository;
    private final TagService tagService;
    private final SlugService slugService;

    @Transactional
    public Note publish(String id, long version) {
        Note note = requirePost(id);
        requireVersion(note, version);
        if (note.getStatus() != NoteStatus.ACTIVE) {
            throw ApiException.invalidState("已归档 / 停用的文章不能发布");
        }

        NoteRevision lastRevision = note.getPublishedRevisionId() == null ? null
                : revisionRepository.findById(note.getPublishedRevisionId()).orElse(null);
        boolean hasChanges = hasChanges(note, lastRevision);

        if (note.getPublishStatus() == PublishStatus.PUBLISHED && !hasChanges) {
            throw ApiException.noChangesToPublish("已发布文章没有未发布更改");
        }

        // 下架后重新发布且没有改动：复用旧快照，不产生无意义 revision。
        if (note.getPublishStatus() == PublishStatus.UNPUBLISHED && !hasChanges && lastRevision != null) {
            note.setPublishStatus(PublishStatus.PUBLISHED);
            note.setLastPublishedAt(Instant.now());
            note.setUpdateBy(SecurityUtils.currentUserId());
            noteRepository.saveAndFlush(note);
            return note;
        }

        validatePublishFields(note);
        note.setSlug(resolveSlug(note));

        NoteRevision previous = revisionRepository.findTopByNoteIdOrderByRevisionNoDesc(note.getId())
                .orElse(null);
        NoteRevision revision = new NoteRevision();
        revision.setNoteId(note.getId());
        revision.setRevisionNo(previous == null ? 1 : previous.getRevisionNo() + 1);
        revision.setTitle(note.getTitle().trim());
        revision.setContent(note.getContent());
        revision.setSummary(note.getSummary().trim());
        revision.setCoverAttachmentId(note.getCoverAttachmentId());
        revision.setCreateBy(SecurityUtils.currentUserId());
        revisionRepository.saveAndFlush(revision);
        tagService.snapshotTags(note.getId(), revision.getId());

        Instant now = Instant.now();
        note.setPublishedRevisionId(revision.getId());
        note.setPublishStatus(PublishStatus.PUBLISHED);
        if (note.getFirstPublishedAt() == null) {
            note.setFirstPublishedAt(now);
        }
        note.setLastPublishedAt(now);
        note.setUpdateBy(SecurityUtils.currentUserId());
        noteRepository.saveAndFlush(note);
        return note;
    }

    @Transactional
    public Note unpublish(String id) {
        Note note = requirePost(id);
        if (note.getPublishStatus() != PublishStatus.PUBLISHED) {
            throw ApiException.invalidState("只有已发布文章可以下架");
        }
        note.setPublishStatus(PublishStatus.UNPUBLISHED);
        note.setUpdateBy(SecurityUtils.currentUserId());
        noteRepository.saveAndFlush(note);
        return note;
    }

    public boolean hasChanges(Note note, NoteRevision revision) {
        if (revision == null) {
            return true;
        }
        return note.getDraftModifiedAt() == null
                || note.getDraftModifiedAt().isAfter(revision.getCreateTime());
    }

    private void validatePublishFields(Note note) {
        if (note.getTitle() == null || note.getTitle().isBlank()) {
            throw ApiException.publishValidation("标题不能为空");
        }
        if (note.getContent() == null || note.getContent().isBlank()) {
            throw ApiException.publishValidation("正文不能为空");
        }
        if (note.getSummary() == null || note.getSummary().isBlank()) {
            throw ApiException.publishValidation("发布前请填写摘要");
        }
    }

    private String resolveSlug(Note note) {
        String slug = note.getSlug();
        if (slug == null || slug.isBlank()) {
            slug = generateSlug(note.getId());
        }
        if (slug.length() > 200 || !SLUG_PATTERN.matcher(slug).matches()) {
            throw ApiException.publishValidation("slug 只能包含小写字母、数字和连字符");
        }
        if (slugService.isTaken(slug, note.getId())) {
            throw ApiException.slugTaken("slug 已被其他文章使用");
        }
        return slug;
    }

    private String generateSlug(String noteId) {
        String date = LocalDate.now(ZoneOffset.UTC).format(SLUG_DATE);
        for (int i = 0; i < 20; i++) {
            String candidate = date + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            if (!slugService.isTaken(candidate, noteId)) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法生成唯一 slug");
    }

    private Note requirePost(String id) {
        return noteRepository.findByIdAndKind(id, NoteKind.POST)
                .orElseThrow(() -> ApiException.notFound("文章不存在"));
    }

    private void requireVersion(Note note, long version) {
        if (note.getVersion() != version) {
            throw ApiException.versionConflict("文章已被修改，请刷新后重试");
        }
    }
}
