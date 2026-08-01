package com.closemore.backend.dto;

import java.time.OffsetDateTime;

/** Outbound DTO for an activity log entry. */
public record ActivityResponse(
        String logId,
        String parentObjectType,
        String parentObjectId,
        String activityType,
        String summary,
        String detailedDescription,
        String attachmentUrl,
        String followUpDate,
        String logDate,
        String loggedByUserId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
