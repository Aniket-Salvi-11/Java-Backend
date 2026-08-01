package com.closemore.backend.tenant;

import com.closemore.backend.domain.CommentReactionEntity;
import com.closemore.backend.domain.TaskAttachmentEntity;
import com.closemore.backend.domain.TaskCommentEntity;
import com.closemore.backend.domain.TaskEntity;
import com.closemore.backend.domain.TaskNotificationEntity;
import com.closemore.backend.repository.CommentReactionRepository;
import com.closemore.backend.repository.TaskAttachmentRepository;
import com.closemore.backend.repository.TaskCommentRepository;
import com.closemore.backend.repository.TaskNotificationRepository;
import com.closemore.backend.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Test-only. Mirrors DealsReadService; see that class for why reads go through a service. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TasksReadService {

    private final TaskRepository tasks;
    private final TaskAttachmentRepository attachments;
    private final TaskCommentRepository comments;
    private final CommentReactionRepository reactions;
    private final TaskNotificationRepository notifications;

    public List<TaskEntity> allTasks() {
        return tasks.findAll();
    }

    public Optional<TaskEntity> taskById(String id) {
        return tasks.findById(id);
    }

    public List<TaskEntity> tasksAssignedTo(String userId) {
        return tasks.findByAssignedTo(userId);
    }

    public List<TaskAttachmentEntity> attachmentsForTask(String taskId) {
        return attachments.findByTaskId(taskId);
    }

    public List<TaskCommentEntity> commentsForTask(String taskId) {
        return comments.findByTaskId(taskId);
    }

    public List<CommentReactionEntity> reactionsForComment(String commentId) {
        return reactions.findByCommentId(commentId);
    }

    public List<TaskNotificationEntity> allNotifications() {
        return notifications.findAll();
    }

    public List<TaskNotificationEntity> unreadNotifications() {
        return notifications.findByReadFalse();
    }
}
