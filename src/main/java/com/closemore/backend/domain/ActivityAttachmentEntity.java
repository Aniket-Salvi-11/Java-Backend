package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps the `activity_attachments` table.
 *
 * <p>The deepest policy in the schema: it joins to activities, then to users, and then re-evaluates
 * the full polymorphic branch on the PARENT activity - including sub-queries into deals or contacts
 * depending on that activity's Parent_Object_Type. Three levels, with a discriminator in the middle.
 *
 * <p>Nothing on this row indicates who may see it, so like line_items it is only ever as visible as
 * its parent. Verified in ActivitiesIsolationIT.
 *
 * <p>Watch {@code Uploaded_At}: TEXT in the DDL, not TIMESTAMPTZ, unlike the Created_At/Updated_At
 * pairs elsewhere. It is mapped as a String deliberately - changing it to a temporal type would
 * fail schema validation.
 */
@Entity
@Table(name = "activity_attachments")
@Getter
@Setter
@NoArgsConstructor
public class ActivityAttachmentEntity {

    @Id
    @Column(name = "Attachment_ID", nullable = false, updatable = false)
    private String attachmentId;

    /** FK -> activities(Log_ID). The join the RLS policy walks first. */
    @Column(name = "Log_ID", nullable = false)
    private String logId;

    @Column(name = "File_Name", nullable = false)
    private String fileName;

    @Column(name = "Mime_Type", nullable = false)
    private String mimeType;

    @Column(name = "File_Size", nullable = false)
    private int fileSize;

    @Column(name = "Storage_Path", nullable = false)
    private String storagePath;

    @Column(name = "Uploaded_By_User_ID", nullable = false)
    private String uploadedByUserId;

    /** TEXT, not a timestamp. See class javadoc. */
    @Column(name = "Uploaded_At", nullable = false)
    private String uploadedAt;
}
