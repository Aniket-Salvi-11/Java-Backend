package com.closemore.backend.tenant;

import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.function.Supplier;

/**
 * Everything the RLS integration tests share: the container wiring, the two-tenant seed, and the
 * {@code asTenant} helper.
 *
 * <p><b>The two-datasource split is the point of this class.</b> Flyway and the application connect
 * to the same database as different roles:
 *
 * <pre>
 *   Flyway  -> closemore      (container superuser)  -- owns the tables, RLS does not apply
 *   Hikari  -> closemore_app  (restricted role)      -- RLS applies, which is what we assert
 * </pre>
 *
 * <p>Setting {@code spring.flyway.url} is what makes Spring Boot build a separate
 * {@code SimpleDriverDataSource} for Flyway instead of borrowing the application's
 * {@code DataSource} (see {@code FlywayAutoConfiguration#getMigrationDataSource}: a non-null JDBC
 * URL short-circuits before the shared-DataSource branch). Two consequences worth knowing:
 * migrations are unaffected by the app role's privileges, and Flyway never takes a connection from
 * the Hikari pool -- so the old "pool of 1 deadlocks Flyway at startup" problem no longer exists.
 * The pool is still pinned small on purpose, so the concurrency test genuinely shares connections
 * between the two tenant threads.
 *
 * <p>Do <b>not</b> add {@code @ServiceConnection} to the container. It contributes a
 * {@code JdbcConnectionDetails} bean that takes precedence over {@code spring.datasource.*}, which
 * would silently put the application back on the superuser connection and make every isolation
 * assertion pass for the wrong reason.
 *
 * <p>The {@code @DynamicPropertySource} method is inherited: Spring collects those methods with
 * {@code MethodIntrospector.selectMethods}, which walks the superclass chain. Subclasses that add
 * no further customisation therefore share a single cached application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractRlsIT {

    @DynamicPropertySource
    static void wireDataSources(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();

        // --- Flyway: superuser, and its own DataSource -----------------------------------------
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);

        // SQL callbacks are discovered by scanning `locations`. `spring.flyway.callbacks` is not a
        // Spring Boot property and was being silently dropped -- this line is the actual fix for
        // "the afterMigrate callback isn't firing".
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/callback");

        // --- Application: restricted role, so RLS is enforced ----------------------------------
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RlsPostgres.APP_USER);
        registry.add("spring.datasource.password", () -> RlsPostgres.APP_PASSWORD);

        // Small on purpose: two tenant threads must contend for the same connections so that a
        // leaked session variable would actually be observable.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @Autowired
    protected RequestUserContextHolder contextHolder;

    /**
     * Seeds two tenants on a SEPARATE SUPERUSER connection, not through the injected (restricted)
     * JdbcTemplate. The app role intentionally cannot bypass RLS, so seeding through it would be
     * filtered by the very policy under test. Production seeding would not go through the
     * RLS-restricted request path either, so this is also the more faithful setup.
     *
     * <p>{@code ON CONFLICT DO NOTHING} keeps this safe to run before every test now that all IT
     * classes share one container.
     */
    @BeforeEach
    void seedTwoTenants() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status","Organization_Name")
                        VALUES
                          ('user-a','A','Owner','a-owner@example.com','Sales_Rep','Active','Acme'),
                          ('user-b','B','Owner','b-owner@example.com','Sales_Rep','Active','Globex')
                        ON CONFLICT ("User_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES
                          ('contact-a','Alpha','Client','alpha@example.com','555-0001','Acme Client Co',
                           'Lead','Test','2026-01-01','user-a'),
                          ('contact-b','Beta','Client','beta@example.com','555-0002','Globex Client Co',
                           'Lead','Test','2026-01-01','user-b')
                        ON CONFLICT ("Contact_ID") DO NOTHING
                        """);
                // One audit row per tenant. events_log has a SERIAL PK and no natural key, so
                // ON CONFLICT cannot dedupe it - the NOT EXISTS guard keeps @BeforeEach idempotent
                // now that every IT class shares one database.
                stmt.execute("""
                        INSERT INTO events_log
                          ("User_ID","User_Name","Action_Type","Object_Type","Object_ID","Object_Name",
                           "Before_State","After_State")
                        SELECT 'user-a','A Owner','DEAL_CREATED','Deal','seed-deal-a','Acme Deal',
                               '{}','{"tenant":"acme"}'
                        WHERE NOT EXISTS (SELECT 1 FROM events_log WHERE "Object_ID" = 'seed-deal-a')
                        """);
                stmt.execute("""
                        INSERT INTO events_log
                          ("User_ID","User_Name","Action_Type","Object_Type","Object_ID","Object_Name",
                           "Before_State","After_State")
                        SELECT 'user-b','B Owner','DEAL_CREATED','Deal','seed-deal-b','Globex Deal',
                               '{}','{"tenant":"globex"}'
                        WHERE NOT EXISTS (SELECT 1 FROM events_log WHERE "Object_ID" = 'seed-deal-b')
                        """);
            }
            connection.commit();
        }
    }

    protected <T> T asTenant(String userId, String role, String tenant, Supplier<T> work) {
        contextHolder.set(new RequestUserContext(userId, role, tenant));
        try {
            return work.get();
        } finally {
            contextHolder.clear();
        }
    }
}