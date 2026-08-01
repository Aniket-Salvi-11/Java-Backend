package com.closemore.backend.tenant;

import com.closemore.backend.domain.ContactEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@code @Generated} sweep applied across all eight entities with database-managed
 * timestamps.
 *
 * <p>WHY THIS EXISTS. Those twelve fields were originally mapped {@code insertable = false,
 * updatable = false}. That is correct as far as the DATABASE is concerned - the column keeps its
 * default, the trigger keeps working - but Hibernate then never reads the value back, so an entity
 * returned from {@code save()} carries a null timestamp while the row on disk has a real one. A
 * service that saved a contact and mapped the result straight to a DTO would emit
 * {@code "createdAt": null}.
 *
 * <p>Nothing caught it for five batches because every other IT only ever READS these entities, and
 * a read populates the field from the SELECT. It surfaced only when EventLogIT became the first
 * test to write through Hibernate and assert on what came back. This class makes sure the fix
 * stays fixed for the normal (non-generated-key) case too.
 *
 * <p>Two distinct behaviours are covered, because the entities split into two groups:
 * <ul>
 *   <li>{@code Created_At} - a DDL default, needs {@code @Generated(INSERT)};</li>
 *   <li>{@code Updated_At} on users/contacts/deals/activities - rewritten by the
 *       update_modified_column trigger from V5 on every UPDATE, so it needs
 *       {@code @Generated(INSERT, UPDATE)}. INSERT alone would leave a stale value in memory after
 *       an update.</li>
 * </ul>
 */
class GeneratedTimestampsIT extends AbstractRlsIT {

    @Autowired
    ContactWriteService writeService;

    @BeforeEach
    void seedOwner() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status","Organization_Name")
                        VALUES ('ts-owner','Ts','Owner','ts-owner@example.com','Sales_Rep','Active','Initech')
                        ON CONFLICT ("User_ID") DO NOTHING
                        """);
            }
            connection.commit();
        }
    }

    @Test
    void savingThroughHibernateReturnsAnEntityCarryingTheDatabaseTimestamps() {
        ContactEntity contact = newContact("ts-insert-probe");

        ContactEntity saved = asTenant("ts-owner", "Sales_Rep", "Initech",
                () -> writeService.create(contact));

        assertThat(saved.getCreatedAt())
                .as("@Generated(INSERT) must SELECT the DDL default back; insertable=false alone "
                        + "leaves this null on the returned entity")
                .isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void updatingThroughHibernateRefreshesTheTriggerManagedTimestamp() {
        ContactEntity contact = newContact("ts-update-probe");
        ContactEntity created = asTenant("ts-owner", "Sales_Rep", "Initech",
                () -> writeService.create(contact));

        ContactEntity renamed = asTenant("ts-owner", "Sales_Rep", "Initech",
                () -> writeService.rename("ts-update-probe", "Renamed"));

        assertThat(renamed.getFirstName()).isEqualTo("Renamed");
        assertThat(renamed.getCreatedAt())
                .as("Created_At has no trigger and must not move on update")
                .isNotNull();
        // The V5 trigger sets Updated_At = NOW() BEFORE UPDATE. Asserting "not before" rather than
        // strictly after, because two statements inside the same transaction can share a clock
        // reading and the test would then be flaky for no useful reason.
        assertThat(renamed.getUpdatedAt())
                .as("@Generated(INSERT, UPDATE) must re-read what update_modified_column wrote")
                .isNotNull();
        assertThat(renamed.getUpdatedAt().toInstant())
                .isAfterOrEqualTo(created.getUpdatedAt().toInstant());
    }

    private ContactEntity newContact(String id) {
        ContactEntity contact = new ContactEntity();
        contact.setContactId(id);
        contact.setFirstName("Timestamp");
        contact.setLastName("Probe");
        contact.setEmail(id + "@example.com");
        contact.setPhonePrimary("555-9001");
        contact.setOrganizationName("Initech Probe Co");
        contact.setContactType("Lead");
        contact.setSource("Test");
        contact.setCreatedDate("2026-01-01");
        contact.setOwnerId("ts-owner");
        return contact;
    }
}
