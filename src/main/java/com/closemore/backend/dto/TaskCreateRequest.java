package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for POST /api/v1/tasks.
 *
 * <p>taskId is absent: the server generates it. assignedBy is absent too - it is always the caller.
 * Accepting it would let one user create work that appears to have come from their manager, which is
 * a social-engineering primitive rather than a feature.
 *
 * <p><b>assignedTo is required and is NOT defaulted to the caller.</b> A task is a thing you give
 * someone; a task with no assignee is a note. The RLS policy derives tenancy from this column, so an
 * assignee outside the caller's organisation is refused by the database - which is also why the
 * service checks it explicitly first, to turn that refusal into a 404 rather than a 403 that would
 * confirm the user id exists somewhere.
 *
 * <p>status defaults to Pending when omitted, so a client creating a task does not have to know the
 * vocabulary.
 */
public record TaskCreateRequest(
        @NotBlank(message = "taskTitle is required") @Size(max = 300) String taskTitle,
        @Size(max = 5000) String description,
        @NotBlank(message = "assignedTo is required") String assignedTo,
        @NotBlank(message = "dueDate is required") @Size(max = 40) String dueDate,
        @Size(max = 60) String status
) {
}
