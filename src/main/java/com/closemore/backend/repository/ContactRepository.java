package com.closemore.backend.repository;

import com.closemore.backend.domain.ContactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for contacts.
 *
 * Same tenancy caveat as UserRepository: isolation comes from RLS + the session variables set per
 * transaction, not from anything in this interface. findAll() returns only the rows the current
 * tenant/role is allowed to see BECAUSE the RLS policy filters them - remove the session-variable
 * setup and this returns nothing (safe failure), not everything.
 *
 * findByOwnerId is here for the Phase 3 owner-scoped list path (canViewAll == false). It is scoped
 * redundantly with RLS on purpose - defence in depth, mirroring how the JS app also checks
 * ownership in code even though RLS would enforce it anyway.
 */
public interface ContactRepository extends JpaRepository<ContactEntity, String> {

    List<ContactEntity> findByOwnerId(String ownerId);
}
