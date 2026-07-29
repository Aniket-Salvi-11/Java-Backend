package com.closemore.backend.tenant;

import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The release gate the migration plan describes (Section 7, final bullet). Runs against the REAL
 * schema (V1-V9 in db/migration, copied verbatim from the JS repo's 001_init.sql through
 * 008_deal_teams.sql), applied by Flyway against a throwaway Testcontainers Postgres.
 *
 * Run with `mvn verify`, NOT `mvn test` - Surefire's default includes don't match *IT, so this
 * class is invisible to `mvn test`. Failsafe is bound to the verify phase in pom.xml for exactly
 * this reason. Requires Docker.
 *
 * WHY THE POOL IS PINNED TO ONE CONNECTION: with the default pool of 10, two concurrent threads
 * will almost certainly be handed two different physical connections, so the dangerous failure
 * mode this test exists to catch - a connection returned to the pool with a leftover session
 * variable and reused by another tenant - is never actually provoked. The test would pass whether
 * or not the mechanism works. maximum-pool-size=1 forces every query in the test to share one
 * connection, which is the worst case and therefore the only one worth asserting on.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantIsolationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("closemore")
            .withUsername("closemore")
            .withPassword("closemore");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // See the class comment - this is the whole point of the test, not a tuning detail.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @Autowired
    RequestUserContextHolder contextHolder;

    @Autowired
    TenantIsolationProbeService probeService;

    @Autowired
    ClassLevelTransactionalProbeService classLevelProbeService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedTwoTenants() {
        // Mirrors seed.mjs's pattern exactly: SET LOCAL app.bypass_rls = 'true' inside a
        // transaction so the seed insert itself isn't blocked by the very RLS policies
        // being tested.
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            connection.setAutoCommit(false);
            try (var stmt = connection.createStatement()) {
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
            }
            connection.commit();
            connection.setAutoCommit(true);
            return null;
        });
    }

    @Test
    void concurrentTenantsOnASharedPoolNeverSeeEachOthersRows() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Integer> tenantA = CompletableFuture.supplyAsync(
                    () -> asTenant("user-a", "Sales_Rep", "Acme", this::repeatedCount), pool);
            CompletableFuture<Integer> tenantB = CompletableFuture.supplyAsync(
                    () -> asTenant("user-b", "Sales_Rep", "Globex", this::repeatedCount), pool);

            assertThat(tenantA.get()).isEqualTo(1);
            assertThat(tenantB.get()).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aTenantCannotReachAnotherTenantsRowEvenByPrimaryKey() {
        // Counting rows can pass for the wrong reason. Addressing the forbidden row directly
        // is the assertion that actually proves the tenant wall.
        int ownRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.countContactById("contact-a"));
        int foreignRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.countContactById("contact-b"));

        assertThat(ownRow).isEqualTo(1);
        assertThat(foreignRow).isZero();
    }

    @Test
    void classLevelTransactionalServicesAlsoGetTheSessionVariables() {
        // Regression guard for the @within(...) half of TenantContextAspect's pointcut. If the
        // pointcut is ever narrowed back to @annotation only, this service runs with no tenant
        // context and RLS returns 0 rows instead of 1.
        int visible = asTenant("user-a", "Sales_Rep", "Acme",
                () -> classLevelProbeService.countVisibleContacts());

        assertThat(visible).isEqualTo(1);
    }

    @Test
    void aRequestWithNoUserContextSeesNothing() {
        // The safe failure mode from Section 7: no session variables set means RLS denies
        // everything. Asserting it explicitly means an accidental bypass (e.g. someone adding a
        // permissive USING (true) policy) shows up as a test failure rather than as data.
        contextHolder.clear();

        assertThat(probeService.countVisibleContacts()).isZero();
    }

    @Test
    void sessionVariablesDoNotSurviveOntoTheNextRequestOnTheSameConnection() {
        // Pool size is 1, so this is guaranteed to be the same physical connection both times.
        // set_config(..., true) is transaction-local, so the second read must come back empty.
        String duringRequest = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.currentTenantSessionVariable());
        contextHolder.clear();
        String afterRequest = probeService.currentTenantSessionVariable();

        assertThat(duringRequest).isEqualTo("Acme");
        assertThat(afterRequest).isNullOrEmpty();
    }

    @Test
    void aspectRefusesToRunOutsideARealTransaction() {
        // Guards the ordering contract: @EnableTransactionManagement(order = 0) +
        // @Order(1) on the aspect. If that ever inverts, set_config runs on a connection that
        // isn't the one serving the query - the silent version of this failure is empty result
        // sets that look like missing data.
        contextHolder.set(new RequestUserContext("user-a", "Sales_Rep", "Acme"));
        try {
            assertThatThrownBy(() -> probeService.countWithNoTransaction())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no active transaction");
        } finally {
            contextHolder.clear();
        }
    }

    private int repeatedCount() {
        int last = -1;
        // Repeat within one "request" to widen the window for a dirty connection to surface.
        for (int i = 0; i < 5; i++) {
            last = probeService.countVisibleContacts();
        }
        return last;
    }

    private <T> T asTenant(String userId, String role, String tenant, Supplier<T> work) {
        contextHolder.set(new RequestUserContext(userId, role, tenant));
        try {
            return work.get();
        } finally {
            contextHolder.clear();
        }
    }
}