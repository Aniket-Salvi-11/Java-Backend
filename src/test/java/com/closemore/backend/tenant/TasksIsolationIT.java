package com.closemore.backend.tenant;

import com.closemore.backend.domain.CommentReactionEntity;
import com.closemore.backend.domain.TaskAttachmentEntity;
import com.closemore.backend.domain.TaskCommentEntity;
import com.closemore.backend.domain.TaskEntity;
import com.closemore.backend.domain.TaskNotificationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Tasks group (V8), whose policies differ from every other group in TWO ways. Both were read
 * from pg_policies in a live database rather than from the migration files, after V9 turned out to
 * silently supersede V4 elsewhere.
 *
 * <p><b>1. Tasks are tenant-only, with no owner or role clause.</b> The entire predicate is "does
 * the ASSIGNEE share my organisation". Any Sales_Rep therefore sees every task in the tenant,
 * plus its attachments, comments and reactions - unlike contacts and deals, where a Sales_Rep is
 * restricted to their own. That is faithful to the JS original and is pinned here rather than
 * quietly tightened.
 *
 * <p><b>2. Notifications are per-USER, the only such table in the schema.</b> The predicate is
 * {@code "User_ID" = current_setting('app.current_user_id')} with no organisation join at all -
 * stricter than everything around it, and the one place an Admin cannot see a colleague's rows.
 *
 * <p>Fixture, all in Initech: {@code task-assignee} holds the task, {@code task-colleague} assigned
 * it and wrote the comment, and each has one notification.
 */
class TasksIsolationIT extends AbstractRlsIT {

    @Autowired
    TasksReadService readService;

    @BeforeEach
    void seedTasks() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status","Organization_Name")
                        VALUES
                          ('task-assignee','Task','Assignee','task-assignee@example.com','Sales_Rep','Active','Initech'),
                          ('task-colleague','Task','Colleague','task-colleague@example.com','Sales_Rep','Active','Initech'),
                          ('task-admin','Task','Admin','task-admin@example.com','Admin','Active','Initech')
                        ON CONFLICT ("User_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO tasks ("Task_ID","Task_Title","Description","Assigned_To","Assigned_By",
                          "Due_Date","Status","Is_Read")
                        VALUES ('task-1','Follow up with client','Call before Friday',
                                'task-assignee','task-colleague','2026-03-01','Open',false)
                        ON CONFLICT ("Task_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO task_attachments ("Attachment_ID","Task_ID","File_Name","Mime_Type",
                          "File_Size","Storage_Path","Uploaded_By")
                        VALUES ('tatt-1','task-1','spec.pdf','application/pdf',1024,
                                '/storage/spec.pdf','task-colleague')
                        ON CONFLICT ("Attachment_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO task_comments ("Comment_ID","Task_ID","User_ID","User_Name","Content")
                        VALUES ('cmt-1','task-1','task-colleague','Task Colleague','On it')
                        ON CONFLICT ("Comment_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO comment_reactions ("Reaction_ID","Comment_ID","User_ID","Emoji")
                        VALUES ('rx-1','cmt-1','task-assignee','thumbsup')
                        ON CONFLICT ("Reaction_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO task_notifications ("Notification_ID","User_ID","Task_ID","Message","Is_Read")
                        VALUES
                          ('ntf-mine','task-assignee','task-1','A task was assigned to you',false),
                          ('ntf-theirs','task-colleague','task-1','You assigned a task',false)
                        ON CONFLICT ("Notification_ID") DO NOTHING
                        """);
            }
            connection.commit();
        }
    }

    @Test
    void theAssigneeSeesTheirTaskAndEverythingHangingOffIt() {
        List<TaskEntity> tasks = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.tasksAssignedTo("task-assignee"));
        List<TaskAttachmentEntity> attachments = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.attachmentsForTask("task-1"));
        List<TaskCommentEntity> comments = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.commentsForTask("task-1"));
        List<CommentReactionEntity> reactions = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.reactionsForComment("cmt-1"));

        assertThat(tasks).extracting(TaskEntity::getTaskId).contains("task-1");
        assertThat(attachments).extracting(TaskAttachmentEntity::getAttachmentId).contains("tatt-1");
        assertThat(comments).extracting(TaskCommentEntity::getCommentId).contains("cmt-1");
        assertThat(reactions).extracting(CommentReactionEntity::getReactionId).contains("rx-1");
    }

    /**
     * Documents difference 1. A colleague who is neither assignee nor assigner still sees the task
     * and its whole comment thread, because the policy checks only the organisation. If this starts
     * failing, someone added an owner clause - probably an improvement, but a deliberate change.
     */
    @Test
    void everyColleagueInTheOrganisationSeesEveryTask() {
        List<TaskEntity> tasks = asTenant("task-colleague", "Sales_Rep", "Initech",
                () -> readService.allTasks());
        List<CommentReactionEntity> reactions = asTenant("task-colleague", "Sales_Rep", "Initech",
                () -> readService.reactionsForComment("cmt-1"));

        assertThat(tasks).extracting(TaskEntity::getTaskId)
                .as("tasks_rls_policy has no owner clause - tenant membership is the whole test")
                .contains("task-1");
        assertThat(reactions).extracting(CommentReactionEntity::getReactionId)
                .as("three hops up: reaction -> comment -> task -> user")
                .contains("rx-1");
    }

    /** Documents difference 2, including that an Admin gets no special access here. */
    @Test
    void notificationsArePerUserNotPerTenant() {
        List<TaskNotificationEntity> mine = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.allNotifications());
        List<TaskNotificationEntity> theirs = asTenant("task-colleague", "Sales_Rep", "Initech",
                () -> readService.allNotifications());
        List<TaskNotificationEntity> adminView = asTenant("task-admin", "Admin", "Initech",
                () -> readService.allNotifications());

        assertThat(mine).extracting(TaskNotificationEntity::getNotificationId)
                .containsExactly("ntf-mine");
        assertThat(theirs).extracting(TaskNotificationEntity::getNotificationId)
                .containsExactly("ntf-theirs");
        assertThat(adminView)
                .as("the one table where Admin does NOT see colleagues' rows")
                .isEmpty();
    }

    @Test
    void theDerivedBooleanQueryOnIsReadResolvesTheRightProperty() {
        // findByReadFalse matches the field named `read`, not `isRead` - same trap as
        // ProductEntity.active. A mismatch fails at context startup, not here.
        List<TaskNotificationEntity> unread = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.unreadNotifications());

        assertThat(unread).extracting(TaskNotificationEntity::getNotificationId)
                .containsExactly("ntf-mine");
    }

    @Test
    void anotherOrganisationSeesNothingFromTheTasksGroup() {
        List<TaskEntity> tasks = asTenant("user-a", "Admin", "Acme",
                () -> readService.allTasks());
        List<TaskAttachmentEntity> attachments = asTenant("user-a", "Admin", "Acme",
                () -> readService.attachmentsForTask("task-1"));
        List<TaskCommentEntity> comments = asTenant("user-a", "Admin", "Acme",
                () -> readService.commentsForTask("task-1"));
        List<CommentReactionEntity> reactions = asTenant("user-a", "Admin", "Acme",
                () -> readService.reactionsForComment("cmt-1"));
        List<TaskNotificationEntity> notifications = asTenant("user-a", "Admin", "Acme",
                () -> readService.allNotifications());

        assertThat(tasks).extracting(TaskEntity::getTaskId).doesNotContain("task-1");
        assertThat(attachments).isEmpty();
        assertThat(comments).isEmpty();
        assertThat(reactions).isEmpty();
        assertThat(notifications).extracting(TaskNotificationEntity::getNotificationId)
                .doesNotContain("ntf-mine", "ntf-theirs");
    }

    @Test
    void allColumnsMapAcrossTheGroup() {
        TaskEntity task = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.taskById("task-1")).orElseThrow();
        TaskAttachmentEntity attachment = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.attachmentsForTask("task-1")).get(0);
        TaskCommentEntity comment = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.commentsForTask("task-1")).get(0);
        CommentReactionEntity reaction = asTenant("task-assignee", "Sales_Rep", "Initech",
                () -> readService.reactionsForComment("cmt-1")).get(0);

        assertThat(task.getTaskTitle()).isEqualTo("Follow up with client");
        assertThat(task.getDescription()).isEqualTo("Call before Friday");
        assertThat(task.getAssignedBy()).isEqualTo("task-colleague");
        assertThat(task.getDueDate()).isEqualTo("2026-03-01");
        assertThat(task.getStatus()).isEqualTo("Open");
        assertThat(task.isRead()).isFalse();
        assertThat(task.getCreatedAt()).isNotNull();

        assertThat(attachment.getFileName()).isEqualTo("spec.pdf");
        assertThat(attachment.getFileSize()).isEqualTo(1024);
        assertThat(attachment.getUploadedBy()).isEqualTo("task-colleague");
        // TIMESTAMPTZ here, unlike ActivityAttachmentEntity.uploadedAt which is TEXT.
        assertThat(attachment.getUploadedAt()).isNotNull();

        assertThat(comment.getUserName()).isEqualTo("Task Colleague");
        assertThat(comment.getContent()).isEqualTo("On it");
        assertThat(comment.getAttachmentUrl()).isNull();
        assertThat(comment.getCreatedAt()).isNotNull();

        assertThat(reaction.getEmoji()).isEqualTo("thumbsup");
        assertThat(reaction.getUserId()).isEqualTo("task-assignee");
    }
}
