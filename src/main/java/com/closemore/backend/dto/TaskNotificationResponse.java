package com.closemore.backend.dto;

import java.time.OffsetDateTime;

/** Outbound DTO for a task notification. */
public record TaskNotificationResponse(
        String notificationId,
        String userId,
        String taskId,
        String message,
        boolean read,
        OffsetDateTime createdAt
) {
}
