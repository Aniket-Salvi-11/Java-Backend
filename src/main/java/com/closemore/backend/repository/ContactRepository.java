package com.closemore.backend.repository;

import com.closemore.backend.domain.ContactEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for contacts.
 *
 * Same tenancy caveat as UserRepository: isolation comes from RLS + the session variables set per
 * transaction, not from anything in this interface. findAll() returns only the rows the current
 * tenant/role is allowed to see BECAUSE the RLS policy filters them - remove the session-variable
 * setup and this returns nothing (safe failure), not everything.
 *
 * findByOwnerId is the owner-scoped list path for when canViewAll() is false. It is scoped
 * redundantly with RLS on purpose - defence in depth, mirroring how the JS app also checks ownership
 * in code even though RLS would enforce it anyway.
 *
 * Phase 3 replaced the List-returning signature with a Pageable-accepting one rather than keeping
 * both. Nothing referenced the List version; leaving it would have left two ways to run the same
 * query, one of which loads every row a rep owns into memory with no ceiling.
 *
 * Pageable.unpaged() is a valid argument and returns everything, which is how the unpaginated list
 * path is served. The COUNT query behind a paged call runs under the same policy as the SELECT, so
 * totalElements is the number of rows this caller may see, not the number that exist.
 */
public interface ContactRepository extends JpaRepository<ContactEntity, String> {

    Page<ContactEntity> findByOwnerId(String ownerId, Pageable pageable);
}