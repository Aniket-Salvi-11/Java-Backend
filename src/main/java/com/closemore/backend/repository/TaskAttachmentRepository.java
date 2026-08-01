package com.closemore.backend.repository;

import com.closemore.backend.domain.TaskAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data JPA repository for task attachments. Visibility is inherited from the parent task. */
public interface TaskAttachmentRepository extends JpaRepository<TaskAttachmentEntity, String> {

    List<TaskAttachmentEntity> findByTaskId(String taskId);
}
