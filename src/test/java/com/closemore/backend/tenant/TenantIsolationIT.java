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

import static org.assertj.core.api.Assertions.assertThat;

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
        // Real schema now exists (V1-V9) - let Flyway apply it.
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    RequestUserContextHolder contextHolder;

    @Autowired
    TenantIsolationProbeService probeService;

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
                    () -> runAsTenant("user-a", "Sales_Rep", "Acme"), pool);
            CompletableFuture<Integer> tenantB = CompletableFuture.supplyAsync(
                    () -> runAsTenant("user-b", "Sales_Rep", "Globex"), pool);

            int countA = tenantA.get();
            int countB = tenantB.get();

            // Each sales rep is the sole seeded user in their tenant and owns exactly one
            // contact - RLS (owner-or-admin, scoped to their Organization_Name) should let
            // them see exactly that one row, never the other tenant's.
            assertThat(countA).isEqualTo(1);
            assertThat(countB).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    private int runAsTenant(String userId, String role, String tenant) {
        contextHolder.set(new RequestUserContext(userId, role, tenant));
        try {
            int last = -1;
            // Repeat on this "request" to increase the odds of catching bleed if a pooled
            // connection were ever handed back to the pool dirty between iterations.
            for (int i = 0; i < 5; i++) {
                last = probeService.countVisibleContacts();
            }
            return last;
        } finally {
            contextHolder.clear();
        }
    }
}
