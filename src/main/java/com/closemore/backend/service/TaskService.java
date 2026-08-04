package com.closemore.backend.service;

import com.closemore.backend.domain.CommentReactionEntity;
import com.closemore.backend.domain.TaskCommentEntity;
import com.closemore.backend.domain.TaskEntity;
import com.closemore.backend.domain.TaskNotificationEntity;
import com.closemore.backend.domain.UserEntity;
import com.closemore.backend.dto.PageResponse;
import com.closemore.backend.dto.ReactionRequest;
import com.closemore.backend.dto.ReactionRollupResponse;
import com.closemore.backend.dto.TaskCommentRequest;
import com.closemore.backend.dto.TaskCommentWithReactionsResponse;
import com.closemore.backend.dto.TaskCreateRequest;
import com.closemore.backend.dto.TaskNotificationResponse;
import com.closemore.backend.dto.TaskResponse;
import com.closemore.backend.dto.TaskUpdateRequest;
import com.closemore.backend.dto.TaskWithNotificationsResponse;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.repository.CommentReactionRepository;
import com.closemore.backend.repository.TaskCommentRepository;
import com.closemore.backend.repository.TaskNotificationRepository;
import com.closemore.backend.repository.TaskRepository;
import com.closemore.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The tasks resource group - seven endpoints covering tasks, their comments, emoji reactions and
 * per-user notifications.
 *
 * <p><b>The tasks policy has no owner clause, and that shapes everything here.</b> Every Sales_Rep
 * in an organisation can see every task in it, which is weaker than the equivalent rules on contacts
 * and deals. That is the Next.js behaviour, recorded in docs/HANDOFF.md rather than fixed. The
 * consequence for this service is that RLS gives almost no write protection: it will happily let one
 * rep edit another's task, so the assignee-or-Admin check on update is not defence in depth here -
 * it is the only thing enforcing the rule.
 *
 * <p><b>Notifications are the one genuinely per-user thing in the schema.</b> task_notifications is
 * the only table whose policy matches on {@code app.current_user_id} with no organisation join, so
 * two users reading the same task legitimately see different notification arrays.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TaskService {

    private static final String OBJECT_TYPE = "Task";
    private static final String COMMENT_OBJECT_TYPE = "TaskComment";

    private static final String DEFAULT_STATUS = "Pending";
    private static final String DONE_STATUS = "Done";

    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "taskId", "taskTitle", "assignedTo", "assignedBy", "dueDate", "status",
            "read", "createdAt");

    private final TaskRepository taskRepository;
    private final TaskCommentRepository taskCommentRepository;
    private final CommentReactionRepository commentReactionRepository;
    private final TaskNotificationRepository taskNotificationRepository;
    private final UserRepository userRepository;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    // --- list -----------------------------------------------------------------------------

    /** GET /api/v1/tasks with no {@code ?page=} - a bare array, matching the Next.js backend. */
    public List<TaskWithNotificationsResponse> listAll(Sort sort) {
        AuthenticatedUser user = currentUserService.require();
        requireSortablePropertiesOnly(sort);

        return withNotifications(taskRepository.findAll(sort), user.userId());
    }

    /** GET /api/v1/tasks?page=... - the opt-in paginated form. */
    public PageResponse<TaskWithNotificationsResponse> listPage(Pageable pageable) {
        AuthenticatedUser user = currentUserService.require();
        requireSortablePropertiesOnly(pageable.getSort());

        Page<TaskEntity> page = taskRepository.findAll(pageable);
        List<TaskWithNotificationsResponse> items =
                withNotifications(page.getContent(), user.userId());

        return new PageResponse<>(items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.hasNext());
    }

    // --- create and update ------------------------------------------------------------------

    /** POST /api/v1/tasks - creates the task and notifies the assignee. */
    public TaskWithNotificationsResponse create(TaskCreateRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        // Read the assignee through the normal path first. The tasks WITH CHECK clause would refuse
        // an assignee outside the tenant anyway, but that arrives as a 403 from an RLS refusal -
        // which confirms to the caller that the user id exists somewhere. A 404 tells them nothing.
        requireVisibleUser(request.assignedTo());

        TaskEntity task = new TaskEntity();
        task.setTaskId(UUID.randomUUID().toString());
        task.setTaskTitle(request.taskTitle());
        task.setDescription(request.description());
        task.setAssignedTo(request.assignedTo());
        // Always the caller. Accepting this would let one user create work that appears to have come
        // from their manager.
        task.setAssignedBy(user.userId());
        task.setDueDate(request.dueDate());
        task.setStatus(blankTo(request.status(), DEFAULT_STATUS));
        // A new task is unread by definition - it is the flag the assignee's badge counts.
        task.setRead(false);

        // saveAndFlush, not save: the INSERT must reach the database inside this method so an RLS
        // refusal surfaces here rather than at commit, where it escapes this transaction's handling.
        TaskEntity saved = taskRepository.saveAndFlush(task);
        TaskResponse response = DtoMapper.toTaskResponse(saved);

        auditService.logCreate(user, OBJECT_TYPE, saved.getTaskId(), saved.getTaskTitle(), response);

        notify(saved.getAssignedTo(), saved.getTaskId(),
                displayName(user.userId()) + " assigned you: " + saved.getTaskTitle());

        return TaskWithNotificationsResponse.of(response, notificationsFor(user.userId(),
                List.of(saved.getTaskId())).getOrDefault(saved.getTaskId(), List.of()));
    }

    /**
     * PUT /api/v1/tasks/{taskId} - assignee or Admin only; notifies the assigner on Done.
     *
     * <p>The authorisation here is doing real work rather than mirroring RLS. The tasks policy is
     * tenant-only, so the database would let any colleague edit this row.
     */
    public TaskWithNotificationsResponse update(String taskId, TaskUpdateRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        TaskEntity task = loadVisible(taskId);
        // requireOwnerOrAdmin with the ASSIGNEE as the owner: the person doing the work owns its
        // status. The assigner deliberately cannot mark their own request complete.
        rbacService.requireOwnerOrAdmin(user, task.getAssignedTo());

        // Snapshot BEFORE mutating. The entity is managed, so the setters below are already
        // reflected in it - capture first or both audit states record the new values.
        TaskResponse before = DtoMapper.toTaskResponse(task);
        boolean wasDone = isDone(task.getStatus());

        task.setTaskTitle(request.taskTitle());
        task.setDescription(request.description());
        task.setDueDate(request.dueDate());
        task.setStatus(request.status());

        TaskEntity saved = taskRepository.saveAndFlush(task);
        TaskResponse after = DtoMapper.toTaskResponse(saved);

        auditService.logUpdate(user, OBJECT_TYPE, taskId, saved.getTaskTitle(), before, after);

        // On the TRANSITION into Done, not on every save of an already-done task. Without the
        // wasDone guard, editing the due date on a completed task would notify the assigner again.
        if (!wasDone && isDone(saved.getStatus()) && !saved.getAssignedBy().equals(user.userId())) {
            notify(saved.getAssignedBy(), taskId,
                    displayName(user.userId()) + " completed: " + saved.getTaskTitle());
        }

        return TaskWithNotificationsResponse.of(after,
                notificationsFor(user.userId(), List.of(taskId)).getOrDefault(taskId, List.of()));
    }

    /**
     * PUT /api/v1/tasks/mark-read - clears the caller's unread markers.
     *
     * <p>Two tables, because the badge is fed by two things: the unread flag on tasks assigned to
     * this user, and their unread notification rows. Clearing only one leaves a count that never
     * reaches zero, which is the bug users actually report.
     *
     * <p>Scoped to the caller in code as well as by policy. For notifications the policy already
     * restricts to {@code app.current_user_id}; for tasks it does not - the tasks policy is
     * tenant-only, so without the explicit assignee filter this would clear the whole
     * organisation's unread flags.
     */
    public int markRead() {
        AuthenticatedUser user = currentUserService.require();

        List<TaskEntity> tasks = taskRepository.findByAssignedToAndReadFalse(user.userId());
        for (TaskEntity task : tasks) {
            task.setRead(true);
        }
        taskRepository.saveAllAndFlush(tasks);

        List<TaskNotificationEntity> notifications =
                taskNotificationRepository.findByUserIdAndReadFalse(user.userId());
        for (TaskNotificationEntity notification : notifications) {
            notification.setRead(true);
        }
        taskNotificationRepository.saveAllAndFlush(notifications);

        return tasks.size() + notifications.size();
    }

    // --- comments and reactions ---------------------------------------------------------------

    /** GET /api/v1/tasks/{taskId}/comments - comments with their emoji rollups. */
    public List<TaskCommentWithReactionsResponse> listComments(String taskId) {
        AuthenticatedUser user = currentUserService.require();
        loadVisible(taskId);

        List<TaskCommentEntity> comments = taskCommentRepository
                .findByTaskId(taskId, Sort.by("createdAt").ascending());
        if (comments.isEmpty()) {
            return List.of();
        }

        Map<String, List<ReactionRollupResponse>> rollups = rollupsFor(comments, user.userId());

        List<TaskCommentWithReactionsResponse> result = new ArrayList<>();
        for (TaskCommentEntity comment : comments) {
            result.add(TaskCommentWithReactionsResponse.of(
                    DtoMapper.toTaskCommentResponse(comment),
                    rollups.getOrDefault(comment.getCommentId(), List.of())));
        }
        return result;
    }

    /** POST /api/v1/tasks/{taskId}/comments */
    public TaskCommentWithReactionsResponse addComment(String taskId, TaskCommentRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        TaskEntity task = loadVisible(taskId);

        TaskCommentEntity comment = new TaskCommentEntity();
        comment.setCommentId(UUID.randomUUID().toString());
        comment.setTaskId(taskId);
        comment.setUserId(user.userId());
        // Denormalised by the schema and NOT NULL. Read from users rather than taken from the
        // request, so a comment cannot be posted under somebody else's display name.
        comment.setUserName(displayName(user.userId()));
        comment.setContent(request.content());
        comment.setAttachmentUrl(request.attachmentUrl());

        TaskCommentEntity saved = taskCommentRepository.saveAndFlush(comment);

        auditService.logCreate(user, COMMENT_OBJECT_TYPE, saved.getCommentId(),
                task.getTaskTitle(), DtoMapper.toTaskCommentResponse(saved));

        // Tell the assignee somebody commented - unless they are the one who commented, which would
        // be a notification about your own typing.
        if (!task.getAssignedTo().equals(user.userId())) {
            notify(task.getAssignedTo(), taskId,
                    displayName(user.userId()) + " commented on: " + task.getTaskTitle());
        }

        // A brand new comment has no reactions; returning the empty list keeps the response shape
        // identical to the list endpoint's, so a client can append it without a special case.
        return TaskCommentWithReactionsResponse.of(DtoMapper.toTaskCommentResponse(saved), List.of());
    }

    /**
     * POST /api/v1/tasks/{taskId}/comments/{commentId}/reactions - toggles one emoji.
     *
     * <p>Toggle rather than add, so there is no delete endpoint: sending the same emoji twice removes
     * it. That matches every chat client and means the client never has to track whether it already
     * reacted.
     *
     * <p>Returns the full rollup for the comment rather than the single reaction, because the button
     * the user just pressed shows a count - and a response carrying only the row that changed would
     * leave the client to recompute it.
     */
    public List<ReactionRollupResponse> toggleReaction(String taskId, String commentId,
                                                       ReactionRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        loadVisible(taskId);
        TaskCommentEntity comment = loadCommentOfTask(taskId, commentId);

        commentReactionRepository
                .findByCommentIdAndUserIdAndEmoji(commentId, user.userId(), request.emoji())
                .ifPresentOrElse(
                        existing -> {
                            commentReactionRepository.delete(existing);
                            commentReactionRepository.flush();
                        },
                        () -> {
                            CommentReactionEntity reaction = new CommentReactionEntity();
                            reaction.setReactionId(UUID.randomUUID().toString());
                            reaction.setCommentId(commentId);
                            reaction.setUserId(user.userId());
                            reaction.setEmoji(request.emoji());
                            commentReactionRepository.saveAndFlush(reaction);
                        });

        return rollupsFor(List.of(comment), user.userId())
                .getOrDefault(commentId, List.of());
    }

    // --- helpers --------------------------------------------------------------------------

    /**
     * Loads a task or throws 404.
     *
     * <p>Under the tenant-only tasks policy this only filters out other organisations - within one
     * organisation every task is visible. The write-side checks are what restrict anything further.
     */
    private TaskEntity loadVisible(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
    }

    /**
     * Loads a comment and checks it belongs to the task named in the path.
     *
     * <p>Not ceremony. The comment policy authorises the row through its OWN task and has no way to
     * know which task the caller claimed in the URL - so without this, a caller could react to any
     * comment in the organisation by pairing its id with a task they can see. Same shape as the
     * line-item check in DealService and the attachment check in AttachmentService.
     */
    private TaskCommentEntity loadCommentOfTask(String taskId, String commentId) {
        TaskCommentEntity comment = taskCommentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found"));

        if (!taskId.equals(comment.getTaskId())) {
            throw new ResourceNotFoundException("Comment not found");
        }
        return comment;
    }

    private void requireVisibleUser(String userId) {
        if (userRepository.findById(userId).isEmpty()) {
            throw new ResourceNotFoundException("Assignee not found");
        }
    }

    /** Attaches each task's notifications for this caller, in one query for the whole page. */
    private List<TaskWithNotificationsResponse> withNotifications(List<TaskEntity> tasks,
                                                                 String userId) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<String> taskIds = tasks.stream().map(TaskEntity::getTaskId).toList();
        Map<String, List<TaskNotificationResponse>> byTask = notificationsFor(userId, taskIds);

        List<TaskWithNotificationsResponse> result = new ArrayList<>();
        for (TaskEntity task : tasks) {
            result.add(TaskWithNotificationsResponse.of(
                    DtoMapper.toTaskResponse(task),
                    byTask.getOrDefault(task.getTaskId(), List.of())));
        }
        return result;
    }

    private Map<String, List<TaskNotificationResponse>> notificationsFor(String userId,
                                                                        List<String> taskIds) {
        Map<String, List<TaskNotificationResponse>> byTask = new LinkedHashMap<>();
        for (TaskNotificationEntity notification
                : taskNotificationRepository.findByUserIdAndTaskIdIn(userId, taskIds)) {
            byTask.computeIfAbsent(notification.getTaskId(), key -> new ArrayList<>())
                    .add(DtoMapper.toTaskNotificationResponse(notification));
        }
        return byTask;
    }

    /**
     * Groups reactions by comment and then by emoji, in one query for all the comments.
     *
     * <p>LinkedHashMap throughout so the emoji order is the order they were first used rather than
     * whatever the hash produces. Reaction buttons jumping around between renders is the kind of
     * thing that looks like a rendering bug and is actually the backend.
     */
    private Map<String, List<ReactionRollupResponse>> rollupsFor(List<TaskCommentEntity> comments,
                                                                 String userId) {
        List<String> commentIds = comments.stream().map(TaskCommentEntity::getCommentId).toList();

        Map<String, Map<String, int[]>> counts = new LinkedHashMap<>();
        Map<String, Set<String>> mine = new LinkedHashMap<>();

        for (CommentReactionEntity reaction : commentReactionRepository.findByCommentIdIn(commentIds)) {
            counts.computeIfAbsent(reaction.getCommentId(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(reaction.getEmoji(), key -> new int[1])[0]++;
            if (userId.equals(reaction.getUserId())) {
                mine.computeIfAbsent(reaction.getCommentId(), key -> new java.util.HashSet<>())
                        .add(reaction.getEmoji());
            }
        }

        Map<String, List<ReactionRollupResponse>> rollups = new LinkedHashMap<>();
        counts.forEach((commentId, byEmoji) -> {
            List<ReactionRollupResponse> list = new ArrayList<>();
            byEmoji.forEach((emoji, count) -> list.add(new ReactionRollupResponse(
                    emoji,
                    count[0],
                    mine.getOrDefault(commentId, Set.of()).contains(emoji))));
            rollups.put(commentId, list);
        });
        return rollups;
    }

    private void notify(String userId, String taskId, String message) {
        TaskNotificationEntity notification = new TaskNotificationEntity();
        notification.setNotificationId(UUID.randomUUID().toString());
        notification.setUserId(userId);
        notification.setTaskId(taskId);
        notification.setMessage(message);
        notification.setRead(false);
        taskNotificationRepository.saveAndFlush(notification);
    }

    /**
     * The actor's display name, for notification text and the denormalised comment column.
     *
     * <p>Falls back to the id rather than failing. A cosmetic field that cannot be resolved is not a
     * reason to abort the operation it decorates - same reasoning as AuditService.
     */
    private String displayName(String userId) {
        return userRepository.findById(userId)
                .map(this::fullName)
                .orElse(userId);
    }

    private String fullName(UserEntity user) {
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }

    /** Case-insensitive: status is free text and "done" is what a client will send half the time. */
    private boolean isDone(String status) {
        return status != null && DONE_STATUS.equalsIgnoreCase(status.trim().toLowerCase(Locale.ROOT));
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void requireSortablePropertiesOnly(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty() + "'");
            }
        }
    }
}
