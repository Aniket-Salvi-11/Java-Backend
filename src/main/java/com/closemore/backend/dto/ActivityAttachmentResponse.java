package com.closemore.backend.dto;

/** Outbound DTO for an activity attachment. uploadedAt is a String - TEXT in the schema. */
public record ActivityAttachmentResponse(
        String attachmentId,
        String logId,
        String fileName,
        String mimeType,
        int fileSize,
        String storagePath,
        String uploadedByUserId,
        String uploadedAt
) {
}
