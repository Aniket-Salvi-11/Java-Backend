package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for PUT /api/v1/tasks/{taskId}.
 *
 * <p>The plan describes this endpoint as updating task status, restricted to the assignee or an
 * Admin. Title, description and due date are accepted alongside it because the same screen edits
 * them and the JS backend takes the whole object.
 *
 * <p><b>assignedTo is not here.</b> Reassigning a task changes which organisation's users can see it -
 * tenancy is derived from that column - so it is a different operation with a different
 * authorisation question, not a field on an edit form. The Next.js backend does not expose it either.
 */
public record TaskUpdateRequest(
        @NotBlank(message = "taskTitle is required") @Size(max = 300) String taskTitle,
        @Size(max = 5000) String description,
        @NotBlank(message = "dueDate is required") @Size(max = 40) String dueDate,
        @NotBlank(message = "status is required") @Size(max = 60) String status
) {
}
