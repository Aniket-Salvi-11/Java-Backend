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
 * Maps the `task_attachments` table from V8__tasks.sql.
 *
 * <p><b>Uploaded_At is TIMESTAMPTZ here, unlike activity_attachments.Uploaded_At which is TEXT.</b>
 * Same column name, same concept, different type in the two tables - so the mapping differs too:
 * OffsetDateTime here, String there. Copying one entity onto the other fails schema validation.
 *
 * <p>Visibility is two hops (task_attachments -> tasks -> users) and inherits the group's
 * tenant-only rule: everyone in the organisation sees every task's attachments.
 */
@Entity
@Table(name = "task_attachments")
@Getter
@Setter
@NoArgsConstructor
public class TaskAttachmentEntity {

    @Id
    @Column(name = "Attachment_ID", nullable = false, updatable = false)
    private String attachmentId;

    /** FK -> tasks(Task_ID). The join the RLS policy walks. */
    @Column(name = "Task_ID", nullable = false)
    private String taskId;

    @Column(name = "File_Name", nullable = false)
    private String fileName;

    @Column(name = "Mime_Type", nullable = false)
    private String mimeType;

    @Column(name = "File_Size", nullable = false)
    private int fileSize;

    @Column(name = "Storage_Path", nullable = false)
    private String storagePath;

    /** FK -> users(User_ID). Note the column is Uploaded_By, not Uploaded_By_User_ID. */
    @Column(name = "Uploaded_By", nullable = false)
    private String uploadedBy;

    /** TIMESTAMPTZ with a DB default - contrast ActivityAttachmentEntity, where this is TEXT. */
    @Column(name = "Uploaded_At", insertable = false, updatable = false)
    private OffsetDateTime uploadedAt;
}
