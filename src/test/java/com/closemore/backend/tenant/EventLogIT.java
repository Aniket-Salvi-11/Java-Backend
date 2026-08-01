package com.closemore.backend.tenant;

import com.closemore.backend.domain.EventLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sixteenth and final Phase 1 entity, and the only one with an auto-generated key.
 *
 * <p>Two things here are not covered anywhere else in the suite:
 * <ul>
 *   <li><b>IDENTITY generation.</b> Every other table takes an application-supplied TEXT id.
 *       Log_Entry_ID is a SERIAL, so this is the one place Hibernate has to read a generated value
 *       back out of an INSERT. {@code TenantIsolationIT} writes audit rows through JdbcTemplate,
 *       which never exercises that path.</li>
 *   <li><b>Writing through Hibernate at all.</b> Every other IT in this suite reads. A WITH CHECK
 *       violation surfacing correctly through Hibernate's flush is worth pinning, because Hibernate
 *       defers the INSERT and the exception therefore arrives from a different place than it would
 *       with plain JDBC.</li>
 * </ul>
 *
 * <p>Tenancy comes from V10, which this codebase added after finding events_log had no policy at
 * all - any role that could reach the table could read every organisation's audit history,
 * including the Before_State/After_State snapshots.
 */
class EventLogIT extends AbstractRlsIT {

    @Autowired
    EventLogReadService readService;

    @BeforeEach
    void seedAuditHistory() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status","Organization_Name")
                        VALUES ('audit-actor','Audit','Actor','audit-actor@example.com','Sales_Rep','Active','Initech')
                        ON CONFLICT ("User_ID") DO NOTHING
                        """);
                // Log_Entry_ID is omitted deliberately - the sequence supplies it, exactly as
                // Hibernate will. Object_ID is a fixed marker so the history query is stable
                // regardless of what other IT classes have written to this shared table.
                stmt.execute("""
                        INSERT INTO events_log ("User_ID","User_Name","Action_Type","Object_Type",
                          "Object_ID","Object_Name","Before_State","After_State")
                        SELECT 'audit-actor','Audit Actor','DEAL_UPDATED','Deal',
                               'audited-deal','Audited Deal','{"stage":"Proposal"}','{"stage":"Closed Won"}'
                        WHERE NOT EXISTS (SELECT 1 FROM events_log WHERE "Object_ID" = 'audited-deal')
                        """);
            }
            connection.commit();
        }
    }

    @Test
    void hibernateGeneratesTheSerialKeyOnInsert() {
        EventLogEntity entry = new EventLogEntity();
        entry.setUserId("audit-actor");
        entry.setUserName("Audit Actor");
        entry.setActionType("TASK_COMPLETED");
        entry.setObjectType("Task");
        entry.setObjectId("generated-key-probe");
        entry.setObjectName("Probe");

        EventLogEntity saved = asTenant("audit-actor", "Sales_Rep", "Initech",
                () -> readService.writeAuditEntry(entry));

        assertThat(saved.getLogEntryId())
                .as("@GeneratedValue(IDENTITY) must read the SERIAL value back from the INSERT")
                .isNotNull()
                .isPositive();
        assertThat(saved.getTimestamp())
                .as("@Generated(INSERT) must SELECT the database default back onto the returned "
                        + "entity - plain insertable=false leaves this null, which is what failed "
                        + "the first time this test ran")
                .isNotNull();

        // Belt and braces: prove the value on the returned entity is the row's real timestamp,
        // not something Hibernate invented client-side. Compared as Instants rather than with
        // isEqualTo, because OffsetDateTime.equals() also compares the zone offset - two reads of
        // the same timestamptz can differ there without differing in the instant they denote.
        EventLogEntity reloaded = asTenant("audit-actor", "Sales_Rep", "Initech",
                () -> readService.historyFor("Task", "generated-key-probe")).get(0);
        assertThat(reloaded.getLogEntryId()).isEqualTo(saved.getLogEntryId());
        assertThat(reloaded.getTimestamp().toInstant()).isEqualTo(saved.getTimestamp().toInstant());
    }

    @Test
    void aTenantCannotWriteAnAuditEntryAgainstAnotherTenantsUser() {
        // The WITH CHECK half of V10's policy, this time through Hibernate rather than
        // JdbcTemplate. Hibernate defers the INSERT to flush, hence saveAndFlush in the service -
        // without it the violation would surface at commit, outside this assertion.
        EventLogEntity forged = new EventLogEntity();
        forged.setUserId("user-a");            // an Acme user
        forged.setUserName("Forged");
        forged.setActionType("DEAL_DELETED");
        forged.setObjectType("Deal");
        forged.setObjectId("forged-object");
        forged.setObjectName("Forged");

        assertThatThrownBy(() -> asTenant("audit-actor", "Sales_Rep", "Initech",
                () -> readService.writeAuditEntry(forged)))
                .hasStackTraceContaining("new row violates row-level security policy");
    }

    @Test
    void theAuditHistoryIsScopedToTheActorsOrganisation() {
        List<EventLogEntity> visible = asTenant("audit-actor", "Sales_Rep", "Initech",
                () -> readService.historyFor("Deal", "audited-deal"));
        List<EventLogEntity> fromAcme = asTenant("user-a", "Admin", "Acme",
                () -> readService.historyFor("Deal", "audited-deal"));

        assertThat(visible).hasSize(1);
        assertThat(fromAcme)
                .as("Before_State/After_State are the most sensitive payload in the schema")
                .isEmpty();
    }

    @Test
    void allColumnsMapIncludingTheNullableStateSnapshots() {
        EventLogEntity entry = asTenant("audit-actor", "Sales_Rep", "Initech",
                () -> readService.historyFor("Deal", "audited-deal")).get(0);

        assertThat(entry.getLogEntryId()).isNotNull();
        assertThat(entry.getTimestamp()).isNotNull();
        assertThat(entry.getUserId()).isEqualTo("audit-actor");
        assertThat(entry.getUserName()).isEqualTo("Audit Actor");
        assertThat(entry.getActionType()).isEqualTo("DEAL_UPDATED");
        assertThat(entry.getObjectType()).isEqualTo("Deal");
        assertThat(entry.getObjectName()).isEqualTo("Audited Deal");
        assertThat(entry.getBeforeState()).contains("Proposal");
        assertThat(entry.getAfterState()).contains("Closed Won");
    }

    @Test
    void aRequestWithNoTenantContextSeesNoAuditHistory() {
        contextHolder.clear();

        assertThat(readService.allEntries()).isEmpty();
    }
}