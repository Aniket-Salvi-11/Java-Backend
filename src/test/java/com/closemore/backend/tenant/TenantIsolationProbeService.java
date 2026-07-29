package com.closemore.backend.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only. Exercises the real `contacts` table (RLS-protected per V3__rls.sql /
 * V4__tenant_rls.sql) through the same @Transactional -> TenantContextAspect -> JdbcTemplate
 * path production services will use, so a passing TenantIsolationIT is actually evidence the
 * wiring works end to end - not just that the echo diagnostic's session variables round-trip.
 *
 * METHOD-level @Transactional here on purpose; see ClassLevelTransactionalProbeService for the
 * class-level case, which the original pointcut did not match.
 */
@Service
@RequiredArgsConstructor
public class TenantIsolationProbeService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public int countVisibleContacts() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Direct lookup by primary key. A count-only assertion can pass for the wrong reason (right
     * number of rows, wrong rows); this asserts the specific row a tenant must NOT be able to
     * reach is genuinely unreachable even when addressed by ID.
     */
    @Transactional
    public int countContactById(String contactId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contacts WHERE \"Contact_ID\" = ?", Integer.class, contactId);
        return count == null ? 0 : count;
    }

    /** Reads back what set_config actually applied on this transaction's connection. */
    @Transactional
    public String currentTenantSessionVariable() {
        return jdbcTemplate.queryForObject(
                "SELECT current_setting('app.current_user_tenant', true)", String.class);
    }

    /**
     * Deliberately annotated so that no transaction is actually opened. TenantContextAspect's
     * pointcut still matches, so this is the direct test of requireActiveTransaction(): the
     * aspect must refuse to set session variables it cannot guarantee will land on the
     * connection that runs the query.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int countWithNoTransaction() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class);
        return count == null ? 0 : count;
    }
}