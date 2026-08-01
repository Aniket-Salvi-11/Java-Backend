package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * Maps the `events_log` audit trail. The sixteenth and final Phase 1 entity.
 *
 * <p><b>The only auto-generated key in the schema.</b> Every other table uses an
 * application-supplied TEXT id; Log_Entry_ID is a classic SERIAL - an integer whose default is
 * {@code nextval('"events_log_Log_Entry_ID_seq"')}, not a true IDENTITY column.
 * {@code GenerationType.IDENTITY} is nevertheless the right mapping: Hibernate 6 implements it on
 * PostgreSQL with {@code INSERT ... RETURNING}, which works against a SERIAL default and needs no
 * generator name. Naming the sequence explicitly via {@code GenerationType.SEQUENCE} would also
 * work but means getting the quoted mixed-case sequence name exactly right, for no benefit.
 *
 * <p><b>Read this before using this entity for WRITES.</b> IDENTITY generation disables JDBC insert
 * batching - Hibernate must round-trip per row to learn each generated id. That is irrelevant for
 * reads and for the occasional single audit entry, but this table is written on every mutation
 * across all 12 resource groups. If audit writes ever appear in a hot path or a bulk import, use
 * JdbcTemplate for the insert and keep this entity for the read side. The
 * TenantIsolationProbeService already writes audit rows that way.
 *
 * <p>Tenancy comes from V10__events_log_rls.sql, which this codebase added after finding the table
 * had no policy at all: visibility is derived by joining to the ACTING user and comparing their
 * organisation. The policy's WITH CHECK clause is what stops one tenant forging an entry in
 * another's audit trail - see TenantIsolationIT.aTenantCannotForgeAnAuditEntryAgainstAnotherTenantsUser.
 *
 * <p>Before_State and After_State hold serialised record snapshots as TEXT. They are the most
 * sensitive payload in the schema and the reason V10 mattered.
 */
@Entity
@Table(name = "events_log")
@Getter
@Setter
@NoArgsConstructor
public class EventLogEntity {

    /** SERIAL. See the class javadoc before writing through this entity. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Log_Entry_ID", nullable = false, updatable = false)
    private Integer logEntryId;

    /**
     * TIMESTAMPTZ DEFAULT now(), database-managed - an audit timestamp the application can set is
     * not much of an audit timestamp.
     *
     * <p><b>@Generated, not insertable=false.</b> Both keep Hibernate from writing the column, but
     * they differ in what happens afterwards. With plain {@code insertable=false} Hibernate omits
     * the column from the INSERT and then never looks at it again, so the in-memory entity returned
     * by {@code save()} still has a null timestamp even though the row in the database has one.
     * That is a quiet trap: a service that saves an audit entry and maps the returned entity
     * straight to a DTO would emit {@code "timestamp": null}.
     *
     * <p>{@code @Generated(event = INSERT)} tells Hibernate the database produces this value, so it
     * issues a SELECT after the INSERT to read it back - the annotation's javadoc names this exact
     * case ("a mapped column has a default value defined in DDL"). Its {@code writable} attribute
     * defaults to false, which is why the explicit insertable/updatable flags are gone rather than
     * merely moved: {@code @Generated} already implies them.
     *
     * <p>The cost is one extra SELECT per insert, which is another reason bulk audit writes belong
     * in JdbcTemplate rather than here - see the class javadoc.
     *
     * <p>NOTE: the same latent trap exists on every other DB-managed timestamp in this codebase
     * (DealEntity.createdAt/updatedAt, TaskEntity.createdAt, TaskAttachmentEntity.uploadedAt and
     * so on). They are all currently mapped insertable=false. It has never shown up because those
     * entities are only ever READ in the test suite, and reads populate the field from the SELECT.
     * Worth sweeping before any of them is written through Hibernate in Phase 3.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "Timestamp", nullable = false)
    private OffsetDateTime timestamp;

    /** FK -> users(User_ID). The acting user, and the column the RLS policy joins on. */
    @Column(name = "User_ID", nullable = false)
    private String userId;

    /** Denormalised display name captured at write time - see TaskCommentEntity.userName. */
    @Column(name = "User_Name", nullable = false)
    private String userName;

    @Column(name = "Action_Type", nullable = false)
    private String actionType;

    /** Polymorphic, like activities.Parent_Object_Type - 'Deal', 'Contact', 'Task' and so on. */
    @Column(name = "Object_Type", nullable = false)
    private String objectType;

    /** Points at whichever table Object_Type names. No FK exists, and none can. */
    @Column(name = "Object_ID", nullable = false)
    private String objectId;

    @Column(name = "Object_Name", nullable = false)
    private String objectName;

    /** Serialised snapshot. Nullable - a creation has no before-state. */
    @Column(name = "Before_State")
    private String beforeState;

    /** Serialised snapshot. Nullable - a deletion has no after-state. */
    @Column(name = "After_State")
    private String afterState;
}