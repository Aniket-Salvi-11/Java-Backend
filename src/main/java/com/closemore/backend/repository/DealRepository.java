package com.closemore.backend.repository;

import com.closemore.backend.domain.DealEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for deals.
 *
 * <p>Same tenancy caveat as ContactRepository: isolation comes from RLS plus the session variables
 * set per transaction, not from anything here. findAll() returns only what the current tenant and
 * role may see because the policy filters it - remove the session-variable setup and this returns
 * nothing (safe), not everything.
 *
 * <p>findByOwnerId is the owner-scoped path for when canViewAll() is false, scoped redundantly with
 * RLS on purpose - defence in depth, mirroring the JS app.
 */
public interface DealRepository extends JpaRepository<DealEntity, String> {

    List<DealEntity> findByOwnerId(String ownerId);
}
