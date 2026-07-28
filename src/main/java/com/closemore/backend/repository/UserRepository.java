package com.closemore.backend.repository;

import com.closemore.backend.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for users.
 *
 * IMPORTANT (reference doc Section 5, Migration Plan Section 7): this repository is NOT tenant-aware
 * on its own. Every finder here still runs through Postgres RLS, which only enforces isolation if
 * the three session variables were set on this transaction's connection by TenantContextAspect.
 * That is why repository methods must always be called from inside a @Transactional service method -
 * never from a non-transactional context, where RLS would default to denying all rows.
 *
 * findByEmailIgnoreCase mirrors the original login query (`LOWER("Email") = $1`). It exists now so
 * the Auth slice in Phase 3 has it, but note: login runs before a tenant context exists, so it will
 * need the bypass path or a dedicated pre-auth mechanism - flagged for Phase 2, not solved here.
 */
public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByEmailIgnoreCase(String email);
}
