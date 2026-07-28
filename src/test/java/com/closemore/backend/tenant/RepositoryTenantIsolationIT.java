package com.closemore.backend.tenant;

import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import com.closemore.backend.domain.ContactEntity;
import com.closemore.backend.repository.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
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
 * THE Phase 1 de-risking test.
 *
 * TenantIsolationIT (Phase 0) proved the mechanism when a JdbcTemplate query runs. This proves it
 * still holds when HIBERNATE runs the query through a Spring Data repository - which is the case
 * that actually matters for every real feature, and the one place the "set_config on the SAME
 * connection as the query" invariant could quietly break.
 *
 * Why it could break, specifically: Hibernate acquires its JDBC connection lazily, and historically
 * could defer acquisition until first statement execution. TenantContextAspect issues set_config via
 * JdbcTemplate at the very start of the @Transactional method. If Hibernate were to run its entity
 * query on a DIFFERENT physical connection than the one JdbcTemplate wrote the session variables to,
 * RLS would see no tenant context and return zero rows - or, worse, a stale one. Spring binds both to
 * the same transaction-scoped connection via DataSourceUtils, so they should match; this test is the
 * evidence, not the assumption. Pool pinned to 1 so "same connection" is forced, not hoped for.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RepositoryTenantIsolationIT {

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    /**
     * Real repository call wrapped in @Transactional so TenantContextAspect fires. This is the
     * production-shaped path: service method -> aspect sets session vars -> repository -> Hibernate.
     */
    @Service
    static class ContactReadService {
        private final ContactRepository contacts;
        ContactReadService(ContactRepository contacts) {
            this.contacts = contacts;
        }

        @Transactional
        List<ContactEntity> listAll() {
            return contacts.findAll();
        }

        @Transactional
        Optional<ContactEntity> byId(String id) {
            return contacts.findById(id);
        }
    }

    @Autowired
    RequestUserContextHolder contextHolder;

    @Autowired
    ContactReadService readService;

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
        // If UserEntity/ContactEntity drifted from the DDL, the application context would not have
        // started (ddl-auto: validate). Reaching this assertion at all means the mappings are
        // schema-exact. The read-back also confirms the trigger-managed Created_At is populated and
        // readable via the entity (insertable=false path).
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
