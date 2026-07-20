package com.closemore.backend.rbac;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Verbatim port of src/lib/rbac.ts (reference doc, Section 4). Five functions, used across
 * every route/service in the original app - nothing is condensed here, matching the reference
 * doc's instruction to port these directly.
 *
 * Usage pattern to preserve when wiring these into Phase 3 services (see reference doc):
 *  - blockExecutiveWrites(user)      -> top of every write path (POST/PUT/DELETE) on
 *                                        Deals, Contacts, Activities, Tasks.
 *  - canViewAll(user)                -> decides whether owner-filtering applies to list queries.
 *  - requireOwnerOrAdmin             -> standard single-resource guard (Contacts, most Deal sub-routes).
 *  - requireOwnerOrTeamOrAdmin       -> Deal detail/update routes specifically (deals can have a team).
 *  - requireRole(user, Role.ADMIN)   -> used directly on Pipelines, Products, Users, Admin routes.
 */
@Service
@RequiredArgsConstructor
public class RbacService {

    private final JdbcTemplate jdbcTemplate;

    /** export const requireRole = (user, ...allowedRoles) => { ... } */
    public void requireRole(AuthenticatedUser user, Role... allowedRoles) {
        if (user == null) {
            throw new RbacException(401, "Not authenticated");
        }
        Set<Role> allowed = Set.of(allowedRoles);
        if (!allowed.contains(user.role())) {
            String allowedList = allowed.stream().map(Role::dbValue).reduce((a, b) -> a + ", " + b).orElse("");
            throw new RbacException(403, "Forbidden - requires one of: " + allowedList);
        }
    }

    /** export const blockExecutiveWrites = (user) => { ... } */
    public void blockExecutiveWrites(AuthenticatedUser user) {
        if (user == null) {
            throw new RbacException(401, "Not authenticated");
        }
        if (user.role() == Role.EXECUTIVE) {
            throw new RbacException(403, "Executive role has read-only access");
        }
    }

    /** export const canViewAll = (user) => { ... } */
    public boolean canViewAll(AuthenticatedUser user) {
        if (user == null) {
            return false;
        }
        return user.role() == Role.ADMIN || user.role() == Role.EXECUTIVE;
    }

    /** export const requireOwnerOrAdmin = (user, ownerId) => { ... } */
    public void requireOwnerOrAdmin(AuthenticatedUser user, String ownerId) {
        if (user == null) {
            throw new RbacException(401, "Not authenticated");
        }
        if (canViewAll(user)) {
            return;
        }
        if (ownerId != null && !ownerId.equals(user.userId())) {
            throw new RbacException(403, "Forbidden - not the resource owner");
        }
    }

    /**
     * export const requireOwnerOrTeamOrAdmin = async (user, dealId, ownerId) => { ... }
     *
     * The original queries deal_team_members directly rather than going through a repository,
     * so this Phase 0 port does the same via JdbcTemplate - a Phase 1 DealTeamMemberRepository
     * can replace this query once the JPA entities exist (step 8), the SQL shape doesn't need
     * to change.
     */
    public void requireOwnerOrTeamOrAdmin(AuthenticatedUser user, String dealId, String ownerId) {
        if (user == null) {
            throw new RbacException(401, "Not authenticated");
        }
        if (canViewAll(user)) {
            return;
        }
        if (ownerId != null && ownerId.equals(user.userId())) {
            return;
        }
        Integer teamMemberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deal_team_members WHERE \"Deal_ID\" = ? AND \"User_ID\" = ?",
                Integer.class, dealId, user.userId());
        if (teamMemberCount == null || teamMemberCount == 0) {
            throw new RbacException(403, "Forbidden - not the resource owner or team member");
        }
    }
}
