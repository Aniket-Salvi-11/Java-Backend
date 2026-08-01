package com.closemore.backend.repository;

import com.closemore.backend.domain.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for tasks.
 *
 * <p>Tenancy caveat with a twist: RLS filters these to the organisation, but NOT to the individual.
 * findAll() returns every task in the tenant, not just the caller's - see TaskEntity. Use
 * findByAssignedTo for "my tasks".
 */
public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    List<TaskEntity> findByAssignedTo(String assignedTo);

    List<TaskEntity> findByStatus(String status);
}
