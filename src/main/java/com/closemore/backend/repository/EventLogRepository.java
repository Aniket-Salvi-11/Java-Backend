package com.closemore.backend.repository;

import com.closemore.backend.domain.EventLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for the audit trail.
 *
 * <p>Read-oriented by intention. Audit WRITES are better done with JdbcTemplate - the entity's
 * IDENTITY key disables insert batching, and this table is written on every mutation across all 12
 * resource groups. See EventLogEntity.
 *
 * <p>findByObjectTypeAndObjectId is the natural history query ("everything that happened to this
 * deal") and mirrors ActivityRepository's polymorphic finder: both halves of the discriminator are
 * needed, since ids from different tables could collide.
 *
 * <p>RLS scopes all of these to the caller's organisation, via the acting user.
 */
public interface EventLogRepository extends JpaRepository<EventLogEntity, Integer> {

    List<EventLogEntity> findByObjectTypeAndObjectId(String objectType, String objectId);

    List<EventLogEntity> findByUserId(String userId);
}
