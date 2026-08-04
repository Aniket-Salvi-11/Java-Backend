package com.closemore.backend.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A task, with the current user's notifications about it attached.
 *
 * <p><b>Why the notifications ride here instead of on their own endpoint.</b> task_notifications has
 * a write route in the inventory ({@code PUT /api/tasks/mark-read}) and no read route, so either
 * they already travel inside the task list or there is a route the review missed. Embedding was the
 * decision: a separate feed is only worth its cost if some UI needs notifications without loading
 * tasks, and nothing in the plan describes one. Adding that endpoint later is one controller method.
 *
 * <p><b>The task fields are flat and the notifications are one added key.</b> That keeps the change
 * purely additive against the Next.js response shape - an existing client deserialising this ignores
 * a key it does not know about, whereas nesting the task under {@code task} would have broken every
 * one of them. Same reasoning as the bare-array decision in PageResponse.
 *
 * <p>The notifications are the CALLER's, not the task's. task_notifications is the only table in the
 * schema whose policy matches on {@code app.current_user_id} with no organisation join, so two users
 * looking at the same task legitimately see different arrays here.
 */
public record TaskWithNotificationsResponse(
        String taskId,
        String taskTitle,
        String description,
        String assignedTo,
        String assignedBy,
        String dueDate,
        String status,
        boolean read,
        OffsetDateTime createdAt,
        List<TaskNotificationResponse> notifications
) {

    public static TaskWithNotificationsResponse of(TaskResponse task,
                                                   List<TaskNotificationResponse> notifications) {
        return new TaskWithNotificationsResponse(
                task.taskId(), task.taskTitle(), task.description(), task.assignedTo(),
                task.assignedBy(), task.dueDate(), task.status(), task.read(), task.createdAt(),
                notifications);
    }
}
