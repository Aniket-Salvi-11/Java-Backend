package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * Maps the `deals` table from V1__init.sql (financial columns added by V7).
 *
 * <p>Three FKs - Associated_Contact_ID, Pipeline_ID, Owner_ID - are all modelled as plain String
 * columns rather than @ManyToOne associations, for the same reason ContactEntity keeps Owner_ID
 * scalar. Here it matters more: deals_rls_policy derives tenancy by joining to users on Owner_ID,
 * and line_items_rls_policy joins through THIS table to get there. A lazy association would invite
 * Hibernate to load the owner or pipeline outside the transaction that set the session variables,
 * and the failure mode is empty results rather than an error. Associations can come later, per
 * read path, with a test alongside.
 *
 * <p>Watch the two date-ish column families, which are easy to conflate:
 * <ul>
 *   <li>{@code Expected_Close_Date} is TEXT - an application-supplied string, like
 *       contacts.Created_Date.</li>
 *   <li>{@code Created_At} / {@code Updated_At} are TIMESTAMPTZ NOT NULL DEFAULT now(), managed by
 *       the database. Mapped insertable=false/updatable=false so Hibernate leaves them alone.</li>
 * </ul>
 *
 * <p>Nullability is taken verbatim from the DDL: only Win_Loss_Reason and Deal_Source are nullable,
 * so every numeric field is a primitive. A wrapper left null would fail the NOT NULL constraint at
 * insert; a primitive defaults to 0, which the column accepts. The six V7 financial columns
 * additionally default to 0 in the database, so Java and Postgres agree.
 */
@Entity
@Table(name = "deals")
@Getter
@Setter
@NoArgsConstructor
public class DealEntity {

    @Id
    @Column(name = "Deal_ID", nullable = false, updatable = false)
    private String dealId;

    @Column(name = "Deal_Name", nullable = false)
    private String dealName;

    /** FK -> contacts(Contact_ID) ON DELETE RESTRICT. Scalar by design. */
    @Column(name = "Associated_Contact_ID", nullable = false)
    private String associatedContactId;

    /** FK -> pipelines(Pipeline_ID) ON DELETE RESTRICT. Scalar by design. */
    @Column(name = "Pipeline_ID", nullable = false)
    private String pipelineId;

    @Column(name = "Current_Stage", nullable = false)
    private String currentStage;

    @Column(name = "Deal_Value", nullable = false)
    private double dealValue;

    /** TEXT column (application-supplied), NOT a timestamp. See class javadoc. */
    @Column(name = "Expected_Close_Date", nullable = false)
    private String expectedCloseDate;

    @Column(name = "Probability_Percentage", nullable = false)
    private int probabilityPercentage;

    /** One of only two nullable columns on this table. */
    @Column(name = "Win_Loss_Reason")
    private String winLossReason;

    /** FK -> users(User_ID) ON DELETE RESTRICT. The column deals_rls_policy joins on. */
    @Column(name = "Owner_ID", nullable = false)
    private String ownerId;

    @Column(name = "Status", nullable = false)
    private String status;

    /** VARCHAR rather than TEXT in the DDL, and nullable. Mapped the same as any other string. */
    @Column(name = "Deal_Source")
    private String dealSource;

    /**
     * Database-managed. {@code @Generated(INSERT)} rather than {@code insertable = false}: both
     * stop Hibernate writing the column, but only @Generated makes it SELECT the value back, so an
     * entity returned from save() carries the real timestamp instead of null. See EventLogEntity
     * for the full reasoning - that is where this trap was found.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "Created_At", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * Trigger-managed (update_modified_column, V5) - the trigger rewrites this on every UPDATE, so
     * the event list includes UPDATE as well as INSERT and Hibernate re-reads it after both. With
     * plain {@code insertable = false} an updated entity would keep its stale in-memory value.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "Updated_At", nullable = false)
    private OffsetDateTime updatedAt;

    // --- Financial columns added by V7__deal_financial_fields.sql, all NOT NULL DEFAULT 0 ---

    @Column(name = "ARR", nullable = false)
    private double arr;

    @Column(name = "TCV", nullable = false)
    private double tcv;

    @Column(name = "TLV", nullable = false)
    private double tlv;

    @Column(name = "Commission", nullable = false)
    private double commission;

    @Column(name = "Partner_Commission", nullable = false)
    private double partnerCommission;

    @Column(name = "Distributor_Commission", nullable = false)
    private double distributorCommission;
}