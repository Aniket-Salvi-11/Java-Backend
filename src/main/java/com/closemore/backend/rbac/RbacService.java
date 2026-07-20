package com.closemore.backend.rbac;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

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
