package com.closemore.backend.repository;

import com.closemore.backend.domain.ContactEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
 * findByOwnerId is the owner-scoped list path for when canViewAll() is false. It is scoped
 * redundantly with RLS on purpose - defence in depth, mirroring how the JS app also checks ownership
 * in code even though RLS would enforce it anyway.
 *
 * TWO OVERLOADS OF findByOwnerId, AND THE DIFFERENCE MATTERS.
 *
 * The Sort overload serves the unpaginated list; the Pageable overload serves the ?page= form.
 * Passing Pageable.unpaged(sort) to the paged method instead is NOT equivalent, and this cost a red
 * CI run: SimpleJpaRepository.findAll(Pageable) short-circuits on an unpaged Pageable to
 *
 *     new PageImpl<>(findAll())
 *
 * which discards the Sort entirely. The rows all come back, so the count is right and nothing
 * throws - only the ORDER BY vanishes, and Postgres returns them in whatever order it likes. The
 * symptom is a list that sorts randomly, which gets reported as a frontend bug.
 *
 * The COUNT query behind a paged call runs under the same policy as the SELECT, so totalElements is
 * the number of rows this caller may see, not the number that exist.
 */
public interface ContactRepository extends JpaRepository<ContactEntity, String> {

    List<ContactEntity> findByOwnerId(String ownerId, Sort sort);

    Page<ContactEntity> findByOwnerId(String ownerId, Pageable pageable);
}