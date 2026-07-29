package com.closemore.backend.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;

class RbacServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RbacService rbac = new RbacService(jdbcTemplate);

    @Test
    void requireRole_rejectsUnauthenticated() {
        assertThatThrownBy(() -> rbac.requireRole(null, Role.ADMIN))
                .isInstanceOf(RbacException.class)
                .extracting(e -> ((RbacException) e).getStatus()).isEqualTo(401);
    }

    @Test
    void requireRole_rejectsDisallowedRole() {
        AuthenticatedUser salesRep = new AuthenticatedUser("u-1", Role.SALES_REP);
        assertThatThrownBy(() -> rbac.requireRole(salesRep, Role.ADMIN))
                .isInstanceOf(RbacException.class)
                .extracting(e -> ((RbacException) e).getStatus()).isEqualTo(403);
    }

    @Test
    void requireRole_allowsMatchingRole() {
        AuthenticatedUser admin = new AuthenticatedUser("u-1", Role.ADMIN);
        rbac.requireRole(admin, Role.ADMIN, Role.EXECUTIVE); // should not throw
    }

    @Test
    void blockExecutiveWrites_rejectsExecutive() {
        AuthenticatedUser exec = new AuthenticatedUser("u-1", Role.EXECUTIVE);
        assertThatThrownBy(() -> rbac.blockExecutiveWrites(exec))
                .isInstanceOf(RbacException.class)
                .extracting(e -> ((RbacException) e).getStatus()).isEqualTo(403);
    }

    @Test
    void blockExecutiveWrites_allowsSalesRepAndAdmin() {
        rbac.blockExecutiveWrites(new AuthenticatedUser("u-1", Role.SALES_REP));
        rbac.blockExecutiveWrites(new AuthenticatedUser("u-1", Role.ADMIN));
    }

    @Test
    void canViewAll_trueForAdminAndExecutiveOnly() {
        assertThat(rbac.canViewAll(new AuthenticatedUser("u-1", Role.ADMIN))).isTrue();
        assertThat(rbac.canViewAll(new AuthenticatedUser("u-1", Role.EXECUTIVE))).isTrue();
        assertThat(rbac.canViewAll(new AuthenticatedUser("u-1", Role.SALES_REP))).isFalse();
        assertThat(rbac.canViewAll(null)).isFalse();
    }

    @Test
    void requireOwnerOrAdmin_salesRepMustOwnTheResource() {
        AuthenticatedUser salesRep = new AuthenticatedUser("u-1", Role.SALES_REP);
        rbac.requireOwnerOrAdmin(salesRep, "u-1"); // owns it - fine

        assertThatThrownBy(() -> rbac.requireOwnerOrAdmin(salesRep, "someone-else"))
                .isInstanceOf(RbacException.class)
                .extracting(e -> ((RbacException) e).getStatus()).isEqualTo(403);
    }

    @Test
    void requireOwnerOrAdmin_adminBypassesOwnershipCheck() {
        AuthenticatedUser admin = new AuthenticatedUser("u-1", Role.ADMIN);
        rbac.requireOwnerOrAdmin(admin, "someone-else"); // should not throw
    }

    @Test
    void requireOwnerOrTeamOrAdmin_fallsBackToTeamMembershipQuery() {
        AuthenticatedUser salesRep = new AuthenticatedUser("u-1", Role.SALES_REP);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("deal-1"), eq("u-1")))
                .thenReturn(1);

        rbac.requireOwnerOrTeamOrAdmin(salesRep, "deal-1", "someone-else"); // should not throw
    }

    @Test
    void requireOwnerOrTeamOrAdmin_rejectsWhenNotOwnerOrTeamMember() {
        AuthenticatedUser salesRep = new AuthenticatedUser("u-1", Role.SALES_REP);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("deal-1"), eq("u-1")))
                .thenReturn(0);

        assertThatThrownBy(() -> rbac.requireOwnerOrTeamOrAdmin(salesRep, "deal-1", "someone-else"))
                .isInstanceOf(RbacException.class)
                .extracting(e -> ((RbacException) e).getStatus()).isEqualTo(403);
    }

    @Test
    void allGuardsRejectAnUnauthenticatedUserWith401() {
        // rbac.ts throws 401 'Not authenticated' from four of the five functions before it does
        // anything else. Missing one of these in the Java port would turn an anonymous request
        // into a 403 (or worse, a pass) instead of a 401 - a contract difference the frontend
        // would see.
        assertThatThrownBy(() -> rbac.requireRole(null, Role.ADMIN))
                .isInstanceOf(RbacException.class)
                .extracting(e -> ((RbacException) e).getStatus()).isEqualTo(401);
        assertThatThrownBy(() -> rbac.blockExecutiveWrites(null))
                .isInstanceOf(RbacException.class)
                .extracting(e -> ((RbacException) e).getStatus()).isEqualTo(401);
        assertThatThrownBy(() -> rbac.requireOwnerOrAdmin(null, "u-1"))
                .isInstanceOf(RbacException.class)
                .extracting(e -> ((RbacException) e).getStatus()).isEqualTo(401);
        assertThatThrownBy(() -> rbac.requireOwnerOrTeamOrAdmin(null, "deal-1", "u-1"))
                .isInstanceOf(RbacException.class)
                .extracting(e -> ((RbacException) e).getStatus()).isEqualTo(401);
    }

    @Test
    void requireOwnerOrAdmin_allowsWhenOwnerIdIsNull() {
        // Matches the original's `if (ownerId && ownerId !== user.User_ID)` - a null/absent owner
        // is NOT treated as a denial. Easy to "improve" during a port and silently change
        // behaviour on records with no owner set.
        rbac.requireOwnerOrAdmin(new AuthenticatedUser("u-1", Role.SALES_REP), null);
    }

    @Test
    void requireOwnerOrTeamOrAdmin_executiveBypassesTheTeamLookupEntirely() {
        rbac.requireOwnerOrTeamOrAdmin(new AuthenticatedUser("u-1", Role.EXECUTIVE), "deal-1", "someone-else");
        verifyNoInteractions(jdbcTemplate);
    }
}
