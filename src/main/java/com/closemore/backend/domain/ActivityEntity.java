package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Maps the `activities` table from V1__init.sql (timestamps from V5).
 *
 * <p><b>POLYMORPHIC PARENT.</b> An activity hangs off either a Deal or a Contact, indicated by
 * Parent_Object_Type with the id in Parent_Object_ID. There is no foreign key on Parent_Object_ID -
 * there cannot be, since its target table varies by row. Two consequences worth internalising:
 * <ul>
 *   <li>Nothing in the database stops a bad Parent_Object_ID. Referential integrity here is the
 *       application's job, unlike everywhere else in this schema.</li>
 *   <li>It CANNOT be modelled as a @ManyToOne. Keeping every FK scalar, as the other entities
 *       already do for different reasons, means this table needs no special treatment.</li>
 * </ul>
 *
 * <p>Its RLS policy branches on that discriminator and offers four routes to read access: you are
 * Admin/Executive; you logged it; its parent is a Deal you own; or its parent is a Contact you own.
 * All four sit behind the requirement that the LOGGER shares your organisation.
 *
 * <p>Note what is absent: deal team membership. V9 added team access to deals, line_items and
 * deal_contacts but did not touch activities, so a team member can open a deal and still not see
 * activities logged on it by someone else. Pinned by ActivitiesIsolationIT rather than assumed -
 * it looks like an oversight in the original, but changing it is a product decision, not a port
 * decision.
 *
 * <p>Log_Date and Follow_Up_Date are TEXT (application-supplied strings); Created_At/Updated_At are
 * the database-managed TIMESTAMPTZ pair. Same split as contacts and deals.
 */
@Entity
@Table(name = "activities")
@Getter
@Setter
@NoArgsConstructor
public class ActivityEntity {

    @Id
    @Column(name = "Log_ID", nullable = false, updatable = false)
    private String logId;

    /** Discriminator - 'Deal' or 'Contact'. The RLS policy branches on this exact string. */
    @Column(name = "Parent_Object_Type", nullable = false)
    private String parentObjectType;

    /** Points at deals(Deal_ID) or contacts(Contact_ID) depending on the type. No FK exists. */
    @Column(name = "Parent_Object_ID", nullable = false)
    private String parentObjectId;

    @Column(name = "Activity_Type", nullable = false)
    private String activityType;

    @Column(name = "Summary", nullable = false)
    private String summary;

    @Column(name = "Detailed_Description", nullable = false)
    private String detailedDescription;

    @Column(name = "Attachment_URL")
    private String attachmentUrl;

    @Column(name = "Follow_Up_Date")
    private String followUpDate;

    /** TEXT column, not a timestamp. */
    @Column(name = "Log_Date", nullable = false)
    private String logDate;

    /** FK -> users(User_ID). The column the RLS policy joins on to derive tenancy. */
    @Column(name = "Logged_By_User_ID", nullable = false)
    private String loggedByUserId;

    @Column(name = "Created_At", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "Updated_At", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
