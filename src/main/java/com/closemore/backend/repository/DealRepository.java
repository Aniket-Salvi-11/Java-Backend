package com.closemore.backend.repository;

import com.closemore.backend.domain.DealEntity;

import java.util.List;
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

    /**
     * Deals sitting on one stage of one pipeline. Added in tranche 6 for the stage-rename cascade
     * in PipelineService.update.
     *
     * <p>Subject to RLS like every other finder here, which is exactly the limitation documented on
     * PipelineService.update: pipelines are global, deals are not, so this returns only the
     * caller's organisation's deals and the reassignment stops at that boundary.
     */
    List<DealEntity> findByPipelineIdAndCurrentStage(String pipelineId, String currentStage);
}