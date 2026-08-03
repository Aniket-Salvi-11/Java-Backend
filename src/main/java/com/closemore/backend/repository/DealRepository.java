package com.closemore.backend.repository;

import com.closemore.backend.domain.DealEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data JPA repository for deals.
 *
 * <p>Same tenancy caveat as ContactRepository: isolation comes from RLS plus the session variables
 * set per transaction, not from anything here. findAll() returns only what the current tenant and
 * role may see because the policy filters it - remove the session-variable setup and this returns
 * nothing (safe), not everything.
 *
 * <p><b>JpaSpecificationExecutor rather than derived query methods.</b> The list endpoint filters on
 * status, stage and owner, any of which may be absent, and applies a visibility restriction on top.
 * As derived methods that is eight combinations; as JPQL with ":param IS NULL" guards it is one
 * query that depends on Hibernate inferring the type of a null parameter, which it does not always
 * do. Specifications compose the same predicates type-safely and are checked by the compiler. See
 * DealSpecifications.
 *
 * <p><b>The old findByOwnerId(String) is gone on purpose.</b> For deals, owner-scoping is not the
 * same as visibility: V9 extended the policy so a team member can see a deal they do not own, and a
 * defence-in-depth branch that filtered on owner alone would be STRICTER than RLS - team deals would
 * silently vanish from the list. DealSpecifications.visibleTo reproduces the policy's actual shape,
 * owner OR team member, which is what defence in depth means here.
 */
public interface DealRepository
        extends JpaRepository<DealEntity, String>, JpaSpecificationExecutor<DealEntity> {
}