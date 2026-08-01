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
 * Maps the `task_comments` table from V8__tasks.sql.
 *
 * <p>User_Name is denormalised - the author's display name copied onto the comment at write time,
 * so a rename does not rewrite history. Keep it in sync deliberately if that is ever wanted; do not
 * assume it matches users.First_Name/Last_Name today.
 *
 * <p>Visibility is two hops (task_comments -> tasks -> users), tenant-only, same as the rest of the
 * group. Note the comment's own User_ID does not gate access: a colleague sees comments written by
 * anyone in the organisation.
 */
@Entity
@Table(name = "task_comments")
@Getter
@Setter
@NoArgsConstructor
public class TaskCommentEntity {

    @Id
    @Column(name = "Comment_ID", nullable = false, updatable = false)
    private String commentId;

    /** FK -> tasks(Task_ID). The join the RLS policy walks. */
    @Column(name = "Task_ID", nullable = false)
    private String taskId;

    /** FK -> users(User_ID). The author. Does NOT gate visibility. */
    @Column(name = "User_ID", nullable = false)
    private String userId;

    /** Denormalised display name captured at write time. See class javadoc. */
    @Column(name = "User_Name", nullable = false)
    private String userName;

    @Column(name = "Content", nullable = false)
    private String content;

    /** The only nullable column on this table. */
    @Column(name = "Attachment_URL")
    private String attachmentUrl;

    /**
     * Database-managed. {@code @Generated(INSERT)} rather than {@code insertable = false}: both
     * stop Hibernate writing the column, but only @Generated makes it SELECT the value back, so an
     * entity returned from save() carries the real timestamp instead of null. See EventLogEntity
     * for the full reasoning - that is where this trap was found.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "Created_At", nullable = false)
    private OffsetDateTime createdAt;
}