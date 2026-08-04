package com.closemore.backend.repository;

import com.closemore.backend.domain.TaskEntity;
import org.springframework.data.domain.Sort;
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

    /**
     * The sorted list behind GET /api/v1/tasks.
     *
     * <p>Sort in SQL, not in Java. A list read without an explicit ORDER BY comes back in whatever
     * order Postgres finds convenient, which is stable enough in testing to look correct and free to
     * change under load - and do NOT reach for Pageable.unpaged(sort) instead, which discards the
     * Sort entirely. See ContactRepository and docs/HANDOFF.md gotcha 13.
     *
     * <p>Everything visible here is tenant-scoped by the policy, which for tasks has NO owner clause:
     * every Sales_Rep sees every task in the organisation, not only their own. That is weaker than
     * the equivalent rules on contacts and deals. It matches the Next.js original and is recorded
     * rather than changed.
     */
    List<TaskEntity> findAll(Sort sort);

    /** Tasks assigned to one user, for the mark-read sweep. */
    List<TaskEntity> findByAssignedToAndReadFalse(String assignedTo);
}
