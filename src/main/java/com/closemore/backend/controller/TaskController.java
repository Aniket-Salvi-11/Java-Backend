package com.closemore.backend.controller;

import com.closemore.backend.dto.ReactionRequest;
import com.closemore.backend.dto.ReactionRollupResponse;
import com.closemore.backend.dto.TaskCommentRequest;
import com.closemore.backend.dto.TaskCommentWithReactionsResponse;
import com.closemore.backend.dto.TaskCreateRequest;
import com.closemore.backend.dto.TaskUpdateRequest;
import com.closemore.backend.dto.TaskWithNotificationsResponse;
import com.closemore.backend.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Tasks, their comments, emoji reactions and per-user notifications.
 *
 * <p>Thin by design: binding and nothing else. Nothing here is on
 * {@code JwtAuthenticationFilter.PUBLIC_PATH_PREFIXES}, so a request without a valid token never
 * reaches these methods.
 *
 * <p><b>{@code PUT /mark-read} and {@code PUT /{taskId}} both match the same shape, and the order
 * they are declared in does not decide which wins.</b> Spring Boot 3's PathPatternParser sorts a
 * literal segment ahead of a template one, so "mark-read" is matched as itself rather than as a task
 * id. That is the behaviour we want and it is not obvious - if it ever changed, or if this were
 * running on the older AntPathMatcher with a different sort, mark-read would silently become a
 * lookup for a task called "mark-read" and answer 404 forever. Pinned by a test rather than trusted.
 *
 * <p>The default sort is by due date, soonest first: a task list is a queue, and the top of it should
 * be the thing that is due next. {@code taskId} is the tiebreaker, because Due_Date has no time
 * component and several tasks a day is normal.
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /**
     * GET /api/v1/tasks
     *
     * <p>The return type is Object because the response shape is genuinely one of two types. Either
     * {@code page} or {@code size} opts the caller in to the envelope.
     */
    @GetMapping
    public Object list(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @PageableDefault(size = 25,
                    sort = {"dueDate", "taskId"},
                    direction = Sort.Direction.ASC) Pageable pageable) {

        boolean paginationRequested = page != null || size != null;

        return paginationRequested
                ? taskService.listPage(pageable)
                : taskService.listAll(pageable.getSort());
    }

    /** POST /api/v1/tasks - creates and assigns, and notifies the assignee. */
    @PostMapping
    public ResponseEntity<TaskWithNotificationsResponse> create(
            @Valid @RequestBody TaskCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskService.create(request));
    }

    /**
     * PUT /api/v1/tasks/mark-read
     *
     * <p>Declared before the {@code {taskId}} mapping for readability only - see the class comment
     * on why the ordering here is not what makes it work.
     *
     * <p>Returns the number of rows cleared rather than 204, because the client uses it to zero a
     * badge and a count is the cheapest way to confirm the sweep actually did something.
     */
    @PutMapping("/mark-read")
    public Map<String, Integer> markRead() {
        return Map.of("markedRead", taskService.markRead());
    }

    /** PUT /api/v1/tasks/{taskId} - assignee or Admin only; notifies the assigner on Done. */
    @PutMapping("/{taskId}")
    public TaskWithNotificationsResponse update(@PathVariable String taskId,
                                                @Valid @RequestBody TaskUpdateRequest request) {
        return taskService.update(taskId, request);
    }

    /** GET /api/v1/tasks/{taskId}/comments - comments with emoji reaction rollups. */
    @GetMapping("/{taskId}/comments")
    public List<TaskCommentWithReactionsResponse> listComments(@PathVariable String taskId) {
        return taskService.listComments(taskId);
    }

    /** POST /api/v1/tasks/{taskId}/comments */
    @PostMapping("/{taskId}/comments")
    public ResponseEntity<TaskCommentWithReactionsResponse> addComment(
            @PathVariable String taskId,
            @Valid @RequestBody TaskCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskService.addComment(taskId, request));
    }

    /**
     * POST /api/v1/tasks/{taskId}/comments/{commentId}/reactions - toggles one emoji.
     *
     * <p>POST for both directions, and 200 rather than 201, because the request is not "create a
     * reaction" - it is "flip my reaction", and half the time it deletes one. A 201 would tell the
     * client something was created when it may have been removed.
     */
    @PostMapping("/{taskId}/comments/{commentId}/reactions")
    public List<ReactionRollupResponse> toggleReaction(@PathVariable String taskId,
                                                       @PathVariable String commentId,
                                                       @Valid @RequestBody ReactionRequest request) {
        return taskService.toggleReaction(taskId, commentId, request);
    }
}
