package com.closemore.backend.repository;

import com.closemore.backend.domain.TaskNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
