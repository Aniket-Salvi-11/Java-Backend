package com.closemore.backend.dto;

import java.time.OffsetDateTime;

/** Outbound DTO for a task attachment. uploadedAt is a timestamp here - contrast the activity one. */
public record TaskAttachmentResponse(
        String attachmentId,
        String taskId,
        String fileName,
        String mimeType,
        int fileSize,
        String storagePath,
        String uploadedBy,
        OffsetDateTime uploadedAt
) {
}
