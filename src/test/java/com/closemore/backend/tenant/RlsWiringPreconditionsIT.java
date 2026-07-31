package com.closemore.backend.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the plumbing rather than the behaviour, so that a regression in the wiring reports
 * itself as "the app is connected as closemore, not closemore_app" instead of as a pile of
 * "expected 0 but was 2" failures in the real isolation tests -- which is the same symptom you get
 * from a genuinely broken policy, and impossible to tell apart from a CI log.
 *
 * <p>Every assertion here corresponds to something that has actually gone wrong in this setup:
 * the role not existing, {@code @ServiceConnection} quietly putting the app back on the superuser,
 * or a new migration adding a tenant table without RLS.
 *
 * <p>These queries hit pg_catalog and deliberately run outside a transaction, so
 * {@code TenantContextAspect} does not fire and no session variables are involved.
 */
class RlsWiringPreconditionsIT extends AbstractRlsIT {

    /** The tables V3/V4 are responsible for. A new tenant-scoped table belongs on this list. */
    private static final String TENANT_TABLES =
            "'users','contacts','deals','line_items','deal_contacts','activities',"
                    + "'activity_attachments','events_log'";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void applicationConnectsAsTheRestrictedRoleNotTheContainerSuperuser() {
        String connectedAs = jdbcTemplate.queryForObject("SELECT current_user", String.class);

        assertThat(connectedAs)
                .as("spring.datasource.username must resolve to the restricted role; if this says "
                        + "'%s' then something (typically @ServiceConnection, or an application.yml "
                        + "override) is winning over @DynamicPropertySource", RlsPostgres.MIGRATION_USER)
                .isEqualTo(RlsPostgres.APP_USER);
    }

    @Test
    void theApplicationRoleCannotBypassRowLevelSecurity() {
        Map<String, Object> attributes = jdbcTemplate.queryForMap(
                "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");

        assertThat((Boolean) attributes.get("rolsuper"))
                .as("a superuser skips RLS entirely, which is the original bug")
                .isFalse();
        assertThat((Boolean) attributes.get("rolbypassrls"))
                .as("BYPASSRLS skips RLS just as thoroughly as superuser")
                .isFalse();
    }

    @Test
    void theApplicationRoleDoesNotOwnTheTablesItQueries() {
        Integer ownedByAppRole = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relkind = 'r'
                  AND pg_get_userbyid(c.relowner) = current_user
                """, Integer.class);

        assertThat(ownedByAppRole)
                .as("an owner bypasses RLS unless FORCE ROW LEVEL SECURITY is set; not owning the "
                        + "tables means isolation does not hinge on that detail")
                .isZero();
    }

    @Test
    void everyTenantScopedTableHasRlsEnabledAndForced() {
        List<String> unprotected = jdbcTemplate.queryForList("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relkind = 'r'
                  AND c.relname IN (%s)
                  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                """.formatted(TENANT_TABLES), String.class);

        assertThat(unprotected)
                .as("tables reachable by the app role with no RLS policy behind them")
                .isEmpty();
    }

    @Test
    void theApplicationRoleCanReadTheTablesItNeeds() {
        // Guards the grant path independently of RLS: a missing GRANT surfaces as
        // "permission denied for table contacts", which is a different failure from
        // "RLS filtered everything out" even though both produce empty results downstream.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege(current_user, 'public.contacts', 'SELECT')", Boolean.class))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege(current_user, 'public.users', 'SELECT')", Boolean.class))
                .isTrue();
    }

    @Test
    void theApplicationRoleCannotTouchFlywaysBookkeeping() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege(current_user, 'public.flyway_schema_history', 'SELECT')",
                Boolean.class))
                .as("the afterMigrate callback revokes this; if it is true the callback did not run")
                .isFalse();
    }
}