package com.closemore.backend.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only. Exercises the real RLS-protected tables through the same
 * {@code @Transactional -> TenantContextAspect -> JdbcTemplate} path production services will use,
 * so a passing TenantIsolationIT is evidence the wiring works end to end - not just that the echo
 * diagnostic's session variables round-trip.
 *
 * <p>METHOD-level {@code @Transactional} here on purpose; see
 * {@link ClassLevelTransactionalProbeService} for the class-level case, which the original pointcut
 * did not match.
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
     * number of rows, wrong rows); this asserts the specific row a tenant must NOT be able to reach
     * is genuinely unreachable even when addressed by ID.
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
     * pointcut still matches, so this is the direct test of requireActiveTransaction(): the aspect
     * must refuse to set session variables it cannot guarantee will land on the connection that
     * runs the query.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int countWithNoTransaction() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------------------------------
    // Added with V10/V11. Before those migrations, countVisibleUsers() with no context returned
    // every user in every tenant, and countVisibleAuditEvents() returned every audit row in the
    // database regardless of context. Both are now tenant-scoped.
    // ------------------------------------------------------------------------------------------

    @Transactional
    public int countVisibleUsers() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Returns the DISTINCT actors visible in the audit trail, rather than a row count. All the ITs
     * now share one database, and one of the tests below writes an audit row, so any assertion on
     * an exact count would depend on test execution order. The set of actors does not.
     */
    @Transactional
    public java.util.List<String> visibleAuditActorIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT \"User_ID\" FROM events_log ORDER BY 1", String.class);
    }

    /** Writes an audit row attributed to {@code actingUserId}. Used to prove WITH CHECK holds. */
    @Transactional
    public void writeAuditEvent(String actingUserId, String objectId) {
        jdbcTemplate.update("""
                INSERT INTO events_log
                  ("User_ID","User_Name","Action_Type","Object_Type","Object_ID","Object_Name")
                VALUES (?, 'probe', 'NOTE_ADDED', 'Contact', ?, 'probe')
                """, actingUserId, objectId);
    }

    /**
     * The pre-auth login door from V11. Called with NO tenant context on purpose - that is the
     * whole point of it, and the reason TenantContextAspect leaves the session variables unset
     * here (ctx == null, so the advice is a no-op).
     */
    @Transactional
    public String loginLookupOrganization(String email) {
        return jdbcTemplate.query(
                "SELECT \"Organization_Name\" FROM auth_lookup_user_by_email(?)",
                rs -> rs.next() ? rs.getString(1) : null,
                email);
    }
}