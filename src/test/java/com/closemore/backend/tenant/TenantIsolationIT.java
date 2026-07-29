package com.closemore.backend.tenant;

import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import org.junit.jupiter.api.BeforeAll;
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
 * The release gate the migration plan describes. Runs against the REAL schema (V1-V9), applied by
 * Flyway against a throwaway Testcontainers Postgres. Run with `mvn verify` (Failsafe), not
 * `mvn test`. Requires Docker.
 *
 * NON-SUPERUSER CONNECTION: Flyway migrates as the container SUPERUSER; the app datasource
 * connects as the restricted 'closemore_app' role (see RlsTestRole). Superusers/table owners
 * bypass RLS unconditionally, so querying as the superuser would make every tenant see every row.
 *
 * POOL SIZE 2: pool of 1 deadlocks Flyway at startup (it needs a connection while the app holds
 * one); 2 is the minimum that lets startup proceed while still allowing the concurrency test's
 * shared-connection window to occur across its repeated queries.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantIsolationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("closemore")
            .withUsername("closemore")
            .withPassword("closemore");

    @BeforeAll
    static void createRestrictedRole() {
        RlsTestRole.create(postgres);
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RlsTestRole.APP_ROLE);
        registry.add("spring.datasource.password", () -> RlsTestRole.APP_PASSWORD);

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
        int ownRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.countContactById("contact-a"));
        int foreignRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.countContactById("contact-b"));

        assertThat(ownRow).isEqualTo(1);
        assertThat(foreignRow).isZero();
    }

    @Test
    void classLevelTransactionalServicesAlsoGetTheSessionVariables() {
        int visible = asTenant("user-a", "Sales_Rep", "Acme",
                () -> classLevelProbeService.countVisibleContacts());

        assertThat(visible).isEqualTo(1);
    }

    @Test
    void aRequestWithNoUserContextSeesNothing() {
        contextHolder.clear();

        assertThat(probeService.countVisibleContacts()).isZero();
    }

    @Test
    void sessionVariablesDoNotSurviveOntoTheNextRequestOnTheSameConnection() {
        String duringRequest = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.currentTenantSessionVariable());
        contextHolder.clear();
        String afterRequest = probeService.currentTenantSessionVariable();

        assertThat(duringRequest).isEqualTo("Acme");
        assertThat(afterRequest).isNullOrEmpty();
    }

    @Test
    void aspectRefusesToRunOutsideARealTransaction() {
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