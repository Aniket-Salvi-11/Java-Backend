package com.closemore.backend.service;

import com.closemore.backend.domain.ActivityAttachmentEntity;
import com.closemore.backend.domain.ActivityEntity;
import com.closemore.backend.dto.ActivityAttachmentResponse;
import com.closemore.backend.ingestion.IngestionEvent;
import com.closemore.backend.ingestion.IngestionEventGateway;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.repository.ActivityAttachmentRepository;
import com.closemore.backend.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Files attached to an activity - the second of the two points where the AI pipeline hooks in.
 *
 * <p><b>Authorisation is inherited from the activity, never checked independently.</b> Every method
 * here loads the parent activity through {@link ActivityService#loadVisible}, so the activities RLS
 * policy decides who may reach the attachment. The attachment's own policy chains through the same
 * activity, which means the two agree by construction rather than by two developers remembering the
 * same rule.
 *
 * <p><b>The exception is delete, which is narrower on purpose.</b> The plan specifies uploader or
 * Admin only: a deal owner can see and download every attachment on their deal, but removing
 * somebody else's uploaded document destroys evidence rather than merely reading it.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AttachmentService {

    private static final String OBJECT_TYPE = "Attachment";

    /**
     * MIME prefixes that make an upload a recording rather than a document.
     *
     * <p>The distinction decides which ingestion event fires, and therefore whether the pipeline
     * sends the file to transcription or to text extraction. Getting it wrong does not fail: the
     * file is simply processed by the wrong service and produces nothing usable, silently.
     */
    private static final String AUDIO_PREFIX = "audio/";
    private static final String VIDEO_PREFIX = "video/";

    private final ActivityAttachmentRepository attachmentRepository;
    private final ActivityService activityService;
    private final StorageProvider storageProvider;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final IngestionEventGateway ingestionEvents;

    /** GET /api/v1/attachments/{logId} - everything attached to one activity. */
    public List<ActivityAttachmentResponse> listForActivity(String logId) {
        currentUserService.require();
        activityService.loadVisible(logId);

        return attachmentRepository.findByLogId(logId).stream()
                .map(DtoMapper::toActivityAttachmentResponse)
                .toList();
    }

    /**
     * POST /api/v1/attachments/{logId} - stores one or more files and fires ingestion for each.
     *
     * <p>All-or-nothing across the batch. The database rows roll back together if any one fails, and
     * because the ingestion events are held until commit, a partial upload also publishes nothing.
     * Stored bytes from the failed batch are orphaned on disk, which is the acceptable half of the
     * trade - see the class comment on delete ordering.
     */
    public List<ActivityAttachmentResponse> upload(String logId, List<UploadedFile> files) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        ActivityEntity activity = activityService.loadVisible(logId);

        if (files == null || files.isEmpty()) {
            throw new BadRequestException("At least one file is required");
        }

        List<ActivityAttachmentResponse> stored = new ArrayList<>();

        for (UploadedFile file : files) {
            if (file.content() == null || file.content().length == 0) {
                throw new BadRequestException("File '" + file.fileName() + "' is empty");
            }

            // The client's filename never becomes a path - the provider generates the real name and
            // returns an opaque handle. See LocalFilesystemStorageProvider.
            String storagePath = storageProvider.store(logId, file.fileName(), file.content());

            ActivityAttachmentEntity attachment = new ActivityAttachmentEntity();
            attachment.setAttachmentId(UUID.randomUUID().toString());
            attachment.setLogId(logId);
            attachment.setFileName(file.fileName());
            attachment.setMimeType(file.mimeType());
            attachment.setFileSize(file.content().length);
            attachment.setStoragePath(storagePath);
            attachment.setUploadedByUserId(user.userId());
            // TEXT, not a timestamp - and the sibling column on task_attachments IS a timestamp.
            // Gotcha 7. ISO-8601 so that lexicographic and chronological order coincide.
            attachment.setUploadedAt(OffsetDateTime.now().toString());

            ActivityAttachmentEntity saved = attachmentRepository.saveAndFlush(attachment);
            ActivityAttachmentResponse response = DtoMapper.toActivityAttachmentResponse(saved);
            stored.add(response);

            auditService.logCreate(user, OBJECT_TYPE, saved.getAttachmentId(),
                    saved.getFileName(), response);

            ingestionEvents.publishAfterCommit(new IngestionEvent(
                    eventTypeFor(file.mimeType()),
                    OBJECT_TYPE,
                    saved.getAttachmentId(),
                    activity.getParentObjectType(),
                    activity.getParentObjectId(),
                    currentUserService.currentTenant(),
                    user.userId(),
                    OffsetDateTime.now()));
        }

        return stored;
    }

    /**
     * GET /api/v1/attachments/{logId}/open/{attachmentId} - the bytes.
     *
     * <p>The logId in the path is checked against the attachment's own, not merely used to find it.
     * Without that, a caller with access to activity A could stream an attachment belonging to
     * activity B by naming it - and the attachment policy would allow it, because it authorises the
     * row through its OWN activity and cannot know which one the caller claimed in the URL. The same
     * shape as the deal line-item check in DealService.
     */
    public DownloadedFile download(String logId, String attachmentId) {
        currentUserService.require();
        activityService.loadVisible(logId);

        ActivityAttachmentEntity attachment = loadVisible(attachmentId);
        if (!logId.equals(attachment.getLogId())) {
            throw new ResourceNotFoundException("Attachment not found");
        }

        return new DownloadedFile(
                attachment.getFileName(),
                attachment.getMimeType(),
                storageProvider.read(attachment.getStoragePath()));
    }

    /** DELETE /api/v1/attachments/{attachmentId} - uploader or Admin only. */
    public void delete(String attachmentId) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        ActivityAttachmentEntity attachment = loadVisible(attachmentId);
        // Narrower than read access, per the plan: a deal owner may download every attachment on
        // their deal but may not destroy one somebody else uploaded.
        rbacService.requireOwnerOrAdmin(user, attachment.getUploadedByUserId());

        ActivityAttachmentResponse before = DtoMapper.toActivityAttachmentResponse(attachment);
        String fileName = attachment.getFileName();

        // Bytes first, row second. A file left on disk after a failed transaction is orphaned
        // storage; a row pointing at a file already gone is a download endpoint that 500s.
        storageProvider.delete(attachment.getStoragePath());
        attachmentRepository.delete(attachment);
        attachmentRepository.flush();

        auditService.logDelete(user, OBJECT_TYPE, attachmentId, fileName, before);
    }

    private ActivityAttachmentEntity loadVisible(String attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found"));
    }

    /**
     * Which pipeline this file belongs in.
     *
     * <p>Audio and video go to transcription; everything else goes to text extraction. A null or
     * unrecognised MIME type is treated as a document, because extraction on a file it cannot read
     * produces nothing, whereas transcription on a PDF wastes a paid API call per upload.
     */
    private String eventTypeFor(String mimeType) {
        String normalised = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return normalised.startsWith(AUDIO_PREFIX) || normalised.startsWith(VIDEO_PREFIX)
                ? IngestionEvent.RECORDING_UPLOADED
                : IngestionEvent.DOCUMENT_UPLOADED;
    }

    /**
     * One incoming file, already read into memory.
     *
     * <p>A plain record rather than Spring's MultipartFile so that this service has no dependency on
     * the web layer - it can be called from a test, a batch import, or a future queue consumer
     * without a servlet request in scope. The controller does the unwrapping.
     */
    public record UploadedFile(String fileName, String mimeType, byte[] content) {
    }

    /** One outgoing file. Same reasoning: no ResponseEntity in the service layer. */
    public record DownloadedFile(String fileName, String mimeType, byte[] content) {
    }
}
