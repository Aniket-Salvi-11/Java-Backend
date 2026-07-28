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
 * Maps the `contacts` table as built by V1__init.sql + V2__columns.sql + V5 (timestamps).
 *
 * Owner_ID is a FK to users with ON DELETE RESTRICT. It is modelled here as a plain String column,
 * NOT a @ManyToOne to UserEntity, on purpose for Phase 1:
 *  - The RLS policy on contacts joins to users on Owner_ID to derive the tenant. A JPA association
 *    would tempt lazy-loading the owner across that same RLS boundary, which muddies what is being
 *    tested. Keeping it a scalar keeps the isolation test unambiguous: one table, one policy.
 *  - Associations can be introduced later where a real read path needs them (e.g. the Deals slice,
 *    which genuinely traverses relationships). Nothing here forecloses that.
 *
 * Note the DDL quirk preserved verbatim: Email and Phone_Primary are NOT NULL on contacts (unlike
 * users.Email which is unique). Created_Date is a TEXT column (application-supplied string), while
 * Created_At/Updated_At are the trigger-managed TIMESTAMPTZ pair - two different things that are
 * easy to conflate. Both are mapped.
 */
@Entity
@Table(name = "contacts")
@Getter
@Setter
@NoArgsConstructor
public class ContactEntity {

    @Id
    @Column(name = "Contact_ID", nullable = false, updatable = false)
    private String contactId;

    @Column(name = "First_Name", nullable = false)
    private String firstName;

    @Column(name = "Last_Name", nullable = false)
    private String lastName;

    @Column(name = "Email", nullable = false)
    private String email;

    @Column(name = "Phone_Primary", nullable = false)
    private String phonePrimary;

    @Column(name = "Organization_Name", nullable = false)
    private String organizationName;

    @Column(name = "Contact_Type", nullable = false)
    private String contactType;

    @Column(name = "Source", nullable = false)
    private String source;

    /** TEXT column in the schema (application-supplied), distinct from the trigger timestamps. */
    @Column(name = "Created_Date", nullable = false)
    private String createdDate;

    /** FK -> users(User_ID) ON DELETE RESTRICT. Scalar by design for Phase 1 (see class javadoc). */
    @Column(name = "Owner_ID", nullable = false)
    private String ownerId;

    @Column(name = "Avatar_Data_URL")
    private String avatarDataUrl;

    @Column(name = "Org_Avatar_Data_URL")
    private String orgAvatarDataUrl;

    @Column(name = "Created_At", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "Updated_At", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
