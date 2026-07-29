package com.closemore.backend.tenant;

import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import com.closemore.backend.domain.ContactEntity;
import com.closemore.backend.repository.ContactRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE Phase 1 de-risking test: proves RLS holds when HIBERNATE runs the query through a Spring
 * Data repository (not just JdbcTemplate). Pool pinned to 2 (1 deadlocks Flyway at startup; 2 is
 * the minimum that lets Flyway migrate while still letting the concurrency window exist).
 *
 * CRITICAL - NON-SUPERUSER CONNECTION: Flyway migrates as the container's default SUPERUSER
 * (correct - DDL needs it), but the application datasource connects as the restricted
 * 'closemore_app' role created in @BeforeAll. Superusers and table owners bypass RLS
 * unconditionally, so if the app queried as the superuser, every tenant would see every row and
 * these assertions would all fail with "expected 0 but was N". See RlsTestRole for the full
 * explanation.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RepositoryTenantIsolationIT.TestBeans.class)
class RepositoryTenantIsolationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("closemore")
            .withUsername("closemore")
            .withPassword("closemore");

    @BeforeAll
    static void createRestrictedRole() {
        // Container is started by the @Container/@Testcontainers lifecycle before @BeforeAll.
        RlsTestRole.create(postgres);
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Flyway uses the SUPERUSER credentials (DDL + FORCE RLS need owner/superuser rights)...
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");

        // ...but the APP datasource (Hibernate + JdbcTemplate) uses the RESTRICTED role, so RLS
        // actually applies to every query the tests make.
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RlsTestRole.APP_ROLE);
        registry.add("spring.datasource.password", () -> RlsTestRole.APP_PASSWORD);

        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        ContactReadService contactReadService(ContactRepository contacts) {
            return new ContactReadService(contacts);
        }
    }

    static class ContactReadService {
        private final ContactRepository contacts;
        ContactReadService(ContactRepository contacts) {
            this.contacts = contacts;
        }

        @Transactional
        public List<ContactEntity> listAll() {
            return contacts.findAll();
        }

        @Transactional
        public Optional<ContactEntity> byId(String id) {
            return contacts.findById(id);
        }
    }

    @Autowired RequestUserContextHolder contextHolder;
    @Autowired ContactReadService readService;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedTwoTenants() {
        // Seeds via the superuser JdbcTemplate? No - JdbcTemplate here uses the app datasource
        // (restricted role), which is subject to RLS. So we bypass RLS explicitly for the seed
        // using SET LOCAL app.bypass_rls, exactly as seed.mjs does, inside one transaction.
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
    void hibernateFindAllReturnsOnlyTheCurrentTenantsRows() {
        List<ContactEntity> asAcme = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.listAll());
        List<ContactEntity> asGlobex = asTenant("user-b", "Sales_Rep", "Globex",
                () -> readService.listAll());

        assertThat(asAcme).extracting(ContactEntity::getContactId).containsExactly("contact-a");
        assertThat(asGlobex).extracting(ContactEntity::getContactId).containsExactly("contact-b");
    }

    @Test
    void hibernateFindByIdCannotLoadAnotherTenantsRow() {
        Optional<ContactEntity> ownRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.byId("contact-a"));
        Optional<ContactEntity> foreignRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.byId("contact-b"));

        assertThat(ownRow).isPresent();
        assertThat(foreignRow).isEmpty();
    }

    @Test
    void entitiesMatchTheRealSchema_validateWouldHaveFailedOtherwise() {
        ContactEntity loaded = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.byId("contact-a")).orElseThrow();

        assertThat(loaded.getOrganizationName()).isEqualTo("Acme Client Co");
        assertThat(loaded.getOwnerId()).isEqualTo("user-a");
        assertThat(loaded.getCreatedAt()).isNotNull();
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