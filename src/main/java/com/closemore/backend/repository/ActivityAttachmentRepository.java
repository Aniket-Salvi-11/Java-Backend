package com.closemore.backend.repository;

import com.closemore.backend.domain.ActivityAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for activity attachments.
 *
 * <p>No owner-scoped finder exists or can exist: the table has no owner or tenant column, and
 * visibility is decided three levels up. See ActivityAttachmentEntity.
 */
public interface ActivityAttachmentRepository extends JpaRepository<ActivityAttachmentEntity, String> {

    List<ActivityAttachmentEntity> findByLogId(String logId);
}
