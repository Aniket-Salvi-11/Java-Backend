package com.closemore.backend.dto;

import java.time.OffsetDateTime;

/** Outbound DTO for a task. */
public record TaskResponse(
        String taskId,
        String taskTitle,
        String description,
        String assignedTo,
        String assignedBy,
        String dueDate,
        String status,
        boolean read,
        OffsetDateTime createdAt
) {
}
