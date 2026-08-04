package com.closemore.backend.repository;

import com.closemore.backend.domain.TaskNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
