package com.closemore.backend.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

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
}
