package com.closemore.backend.repository;

import com.closemore.backend.domain.TaskCommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data JPA repository for task comments. Visibility is inherited from the parent task. */
public interface TaskCommentRepository extends JpaRepository<TaskCommentEntity, String> {

    List<TaskCommentEntity> findByTaskId(String taskId);
}
