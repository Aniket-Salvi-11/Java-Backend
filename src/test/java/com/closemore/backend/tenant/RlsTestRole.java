package com.closemore.backend.tenant;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Test-only helper. Creates a NON-SUPERUSER role inside the Testcontainers Postgres and grants it
 * exactly the table privileges the application needs.
 *
 * WHY THIS EXISTS (the bug this fixes):
 * Testcontainers' default Postgres user (the one from withUsername) is a SUPERUSER. PostgreSQL
 * RLS - even ENABLE + FORCE ROW LEVEL SECURITY - is UNCONDITIONALLY bypassed by superusers and by
 * the table owner. Flyway runs the migrations as that superuser (correct - DDL needs the rights),
 * and if the app/tests ALSO query as that superuser, every RLS policy is skipped and every tenant
 * sees every row. That is exactly the "expected 0 but was 2" failures we saw: not misset session
 * variables (those would DENY all rows), but RLS not being enforced at all.
 *
 * In production the app connects as a normal, non-superuser role and RLS applies. The test must
 * mirror that to actually prove isolation. So: migrate as the superuser, but run the application
 * datasource as this restricted role.
 *
 * The role is NOT the table owner and is NOT a superuser, so both RLS bypass paths are closed and
 * FORCE ROW LEVEL SECURITY takes effect as designed.
 */
final class RlsTestRole {

    static final String APP_ROLE = "closemore_app";
    static final String APP_PASSWORD = "closemore_app_pw";

    private RlsTestRole() {
    }

    /**
     * Runs once against the superuser connection after the container is up. Creates the restricted
     * role and grants it CRUD on all current tables (plus USAGE on sequences for the SERIAL PK on
     * events_log). Idempotent enough for a fresh container.
     */
    static void create(PostgreSQLContainer<?> postgres) {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = conn.createStatement()) {

            st.execute("DROP ROLE IF EXISTS " + APP_ROLE);
            st.execute("CREATE ROLE " + APP_ROLE
                    + " LOGIN PASSWORD '" + APP_PASSWORD + "' NOSUPERUSER NOBYPASSRLS");

            // Privileges: the app reads and writes rows, but owns nothing and cannot alter schema.
            st.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APP_ROLE);
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_ROLE);

            // NOBYPASSRLS is the belt; being a non-owner non-superuser is the suspenders. Both set.
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create restricted RLS test role", e);
        }
    }
}
