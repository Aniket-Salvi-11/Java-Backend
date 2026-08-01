package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Maps the `task_notifications` table from V8__tasks.sql.
 *
 * <p><b>The only table in the schema whose policy is per-USER rather than per-tenant.</b> The
 * entire predicate is {@code "User_ID" = current_setting('app.current_user_id')}. No join to users,
 * no organisation check, no role clause.
 *
 * <p>Two consequences:
 * <ul>
 *   <li>You see only your own notifications - stricter than the rest of the Tasks group, where
 *       colleagues see everything.</li>
 *   <li>Tenancy is enforced only transitively, via the user id. An Admin cannot read another
 *       user's notifications even inside their own organisation, which is almost certainly right
 *       for a notification inbox but differs from every other table's Admin behaviour.</li>
 * </ul>
 *
 * <p>Pinned by TasksIsolationIT.notificationsArePerUserNotPerTenant.
 */
@Entity
@Table(name = "task_notifications")
@Getter
@Setter
@NoArgsConstructor
public class TaskNotificationEntity {

    @Id
    @Column(name = "Notification_ID", nullable = false, updatable = false)
    private String notificationId;

    /** FK -> users(User_ID). The recipient, and the ENTIRE RLS predicate for this table. */
    @Column(name = "User_ID", nullable = false)
    private String userId;

    /** FK -> tasks(Task_ID). */
    @Column(name = "Task_ID", nullable = false)
    private String taskId;

    @Column(name = "Message", nullable = false)
    private String message;

    /** Field is `read`, not `isRead` - see ProductEntity.active. */
    @Column(name = "Is_Read", nullable = false)
    private boolean read;

    @Column(name = "Created_At", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
