package com.closemore.backend.repository;

import com.closemore.backend.domain.TaskNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data JPA repository for task notifications.
 *
 * <p>Unusually, findAll() here is already per-user: the RLS policy on this table matches on
 * app.current_user_id, so a caller only ever sees their own rows. findByUserId is therefore
 * redundant for the current user and returns nothing for anyone else - which is the intended
 * behaviour, not a bug.
 */
public interface TaskNotificationRepository extends JpaRepository<TaskNotificationEntity, String> {

    List<TaskNotificationEntity> findByUserId(String userId);

    List<TaskNotificationEntity> findByReadFalse();

    /**
     * One user's notifications about a set of tasks, in one query.
     *
     * <p>Feeds the task list, where each task carries the caller's own notifications. Per-task
     * fetching would be N+1 on the main screen of the application.
     *
     * <p>The userId argument is redundant with the policy - task_notifications is the only table
     * whose policy matches on app.current_user_id with no organisation join, so a caller cannot see
     * anyone else's rows anyway. It is passed explicitly as defence in depth, the same way the
     * services keep RbacService checks that RLS already enforces.
     */
    List<TaskNotificationEntity> findByUserIdAndTaskIdIn(String userId, Collection<String> taskIds);

    /** The mark-read sweep: everything of mine still unread. */
    List<TaskNotificationEntity> findByUserIdAndReadFalse(String userId);

    /**
     * Writes one notification. THE HAND-WRITTEN SQL IS THE POINT - do not replace this with
     * {@code save()} or {@code saveAndFlush()}.
     *
     * <p>{@code Created_At} is mapped {@code @Generated(INSERT)} on TaskNotificationEntity, so
     * Hibernate appends {@code RETURNING "Created_At"} to its insert in order to read the
     * database-assigned value back. Under row-level security an {@code INSERT ... RETURNING} must
     * satisfy the SELECT policy as well as WITH CHECK: you may only use RETURNING on a row you are
     * allowed to read. This table's USING clause is
     * {@code "User_ID" = current_setting('app.current_user_id')} and V16 deliberately left it that
     * way, because per-user reads are the property that makes a notification inbox private even
     * from an Admin.
     *
     * <p>So every notification addressed to somebody else - which is every notification worth
     * sending - passed WITH CHECK and then failed on the read-back, with
     * {@code new row violates row-level security policy for table "task_notifications"}. That
     * message names WITH CHECK, not the SELECT policy, which is why V16 looked like the fix and
     * was not: it was necessary and insufficient. The self-notify path kept passing throughout,
     * because there the row IS readable by its writer.
     *
     * <p>This statement omits RETURNING, so no read-back is attempted and the write succeeds on the
     * widened WITH CHECK alone. The insert still cannot cross a tenant boundary; that half is
     * enforced by V16 and pinned by
     * TaskApiIT.aNotificationCanBeWrittenForAColleagueButNotAcrossTenants, which also pins the
     * RETURNING refusal so this comment cannot quietly stop being true.
     *
     * <p>{@code flushAutomatically = true} keeps the ordering the old saveAndFlush gave: the task
     * row reaches the database before the notification that references it by foreign key.
     * {@code clearAutomatically} stays false - clearing would detach the entities the calling
     * method is still holding.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO task_notifications
              ("Notification_ID", "User_ID", "Task_ID", "Message", "Is_Read")
            VALUES (:notificationId, :userId, :taskId, :message, FALSE)
            """, nativeQuery = true)
    void insertNotification(@Param("notificationId") String notificationId,
                            @Param("userId") String userId,
                            @Param("taskId") String taskId,
                            @Param("message") String message);
}