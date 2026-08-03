package com.closemore.backend.service;

import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.DealTeamMemberEntity;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/**
 * Criteria fragments for the deal list endpoint.
 *
 * <p>Each method returns a predicate or null, and null contributes nothing when combined - so an
 * absent filter costs no SQL rather than becoming a tautology the planner has to see through.
 *
 * <p><b>visibleTo is the one that matters.</b> It reproduces, in Java, the shape of the deals RLS
 * policy as V9 left it: a Sales_Rep sees a deal if they own it OR if they are on its team. The
 * database already enforces exactly this, so the predicate cannot change any result today. It is
 * here for the same reason the RbacService calls are in the services - if a future migration ever
 * weakens the policy, this still holds, and if this is refactored away, the policy still holds.
 *
 * <p>Getting the shape wrong in the OTHER direction is the real risk and the reason this is not just
 * an owner check: filtering on {@code ownerId = me} alone is STRICTER than the policy, so team deals
 * would disappear from the list with no error anywhere - the failure mode that looks like a UI bug
 * and gets debugged in the frontend for a day.
 */
public final class DealSpecifications {

    private DealSpecifications() {
    }

    /** Restricts to deals the given user may see: owned by them, or with them on the team. */
    public static Specification<DealEntity> visibleTo(String userId) {
        return (root, query, cb) -> {
            // A correlated EXISTS rather than a join: a join to deal_team_members would multiply
            // rows for a deal with several members, and the resulting duplicates would make the
            // paginated COUNT disagree with the number of deals actually returned.
            Subquery<String> teamMembership = query.subquery(String.class);
            Root<DealTeamMemberEntity> member = teamMembership.from(DealTeamMemberEntity.class);
            teamMembership.select(member.get("id").get("userId"))
                    .where(cb.and(
                            cb.equal(member.get("id").get("dealId"), root.get("dealId")),
                            cb.equal(member.get("id").get("userId"), userId)));

            return cb.or(cb.equal(root.get("ownerId"), userId), cb.exists(teamMembership));
        };
    }

    public static Specification<DealEntity> hasStatus(String status) {
        return blank(status) ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<DealEntity> hasStage(String stage) {
        return blank(stage) ? null : (root, query, cb) -> cb.equal(root.get("currentStage"), stage);
    }

    public static Specification<DealEntity> hasOwner(String ownerId) {
        return blank(ownerId) ? null : (root, query, cb) -> cb.equal(root.get("ownerId"), ownerId);
    }

    /**
     * Combines the fragments, skipping the nulls.
     *
     * <p>Written out rather than chained with {@code Specification.where(...).and(...)} because that
     * chain returns a specification whose {@code toPredicate} can itself return null when every part
     * is absent, and Spring Data treats a null specification and a specification-returning-null
     * differently depending on the call site. Returning an explicit conjunction of whatever survived
     * removes the question.
     */
    @SafeVarargs
    public static Specification<DealEntity> allOf(Specification<DealEntity>... parts) {
        return (root, query, cb) -> {
            Predicate combined = cb.conjunction();
            for (Specification<DealEntity> part : parts) {
                if (part == null) {
                    continue;
                }
                Predicate predicate = part.toPredicate(root, query, cb);
                if (predicate != null) {
                    combined = cb.and(combined, predicate);
                }
            }
            return combined;
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
