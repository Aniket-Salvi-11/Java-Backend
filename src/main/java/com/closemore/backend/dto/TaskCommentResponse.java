package com.closemore.backend.dto;

import java.time.OffsetDateTime;

/** Outbound DTO for a task comment. userName is denormalised - see TaskCommentEntity. */
public record TaskCommentResponse(
        String commentId,
        String taskId,
        String userId,
        String userName,
        String content,
        String attachmentUrl,
        OffsetDateTime createdAt
) {
}
