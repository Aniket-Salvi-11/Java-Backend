package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for POST /api/v1/tasks/{taskId}/comments.
 *
 * <p>userId and userName are absent: both come from the authenticated caller. userName is denormalised
 * into the row by the schema, so the service reads it from the users table rather than trusting a
 * client-supplied display name - otherwise a comment could be posted under somebody else's name.
 */
public record TaskCommentRequest(
        @NotBlank(message = "content is required") @Size(max = 5000) String content,
        @Size(max = 2000) String attachmentUrl
) {
}
