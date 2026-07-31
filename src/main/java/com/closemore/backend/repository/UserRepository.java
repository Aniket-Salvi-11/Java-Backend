package com.closemore.backend.repository;

import com.closemore.backend.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for users.
 *
 * <p>IMPORTANT (reference doc Section 5, Migration Plan Section 7): this repository is NOT
 * tenant-aware on its own. Every finder here still runs through Postgres RLS, which only enforces
 * isolation if the three session variables were set on this transaction's connection by
 * TenantContextAspect. That is why repository methods must always be called from inside a
 * {@code @Transactional} service method - never from a non-transactional context, where RLS would
 * default to denying all rows.
 *
 * <p>PRE-AUTH LOOKUP IS ALREADY SOLVED - DO NOT ADD A BYPASS.
 *
 * <p>{@code findByEmailIgnoreCase} mirrors the original login query ({@code LOWER("Email") = $1}),
 * and like every other finder here it is subject to RLS: called with no tenant context it returns
 * nothing. That is correct behaviour, not a bug to work around.
 *
 * <p>Authentication - which by definition runs before a tenant context exists - goes through
 * {@code auth_lookup_user_by_email(TEXT)} instead. It is a SECURITY DEFINER function added in
 * {@code V11__users_rls_tighten.sql} that answers exactly one question ("which user has this
 * email?") and returns at most one row. It cannot enumerate the directory.
 *
 * <p>An earlier version of this comment said pre-auth would "need the bypass path or a dedicated
 * pre-auth mechanism - flagged for Phase 2, not solved here". It is solved. The reason that
 * matters: V4's users_rls_policy used to end with
 * {@code OR current_setting('app.current_user_tenant', true) IS NULL}, which let ANY context-less
 * query read every user in every organisation. V11 removed that clause and replaced it with the
 * narrow function. Reintroducing a blanket bypass to make a finder here work would reopen exactly
 * the hole V11 closed - read V11's header before going down that road.
 *
 * <p>Guarded by {@code TenantIsolationIT.theLoginDoorResolvesAUserWithoutOpeningTheDirectory} and
 * {@code aRequestWithNoUserContextCannotEnumerateTheUserDirectory}.
 */
public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByEmailIgnoreCase(String email);
}