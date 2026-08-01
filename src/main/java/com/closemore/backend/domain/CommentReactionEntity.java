package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps the `comment_reactions` table from V8__tasks.sql.
 *
 * <p>Three hops - comment_reactions -> task_comments -> tasks -> users - which makes this the
 * longest join chain in the schema alongside activity_attachments. Still tenant-only at the end
 * of it.
 *
 * <p>Note the primary key is a surrogate Reaction_ID, NOT (Comment_ID, User_ID). So nothing in the
 * database stops the same user reacting twice to the same comment with the same emoji; if the
 * product needs one-reaction-per-user-per-emoji, that is a unique constraint the schema does not
 * currently have.
 */
@Entity
@Table(name = "comment_reactions")
@Getter
@Setter
@NoArgsConstructor
public class CommentReactionEntity {

    @Id
    @Column(name = "Reaction_ID", nullable = false, updatable = false)
    private String reactionId;

    /** FK -> task_comments(Comment_ID). First hop of the three-level policy. */
    @Column(name = "Comment_ID", nullable = false)
    private String commentId;

    /** FK -> users(User_ID). Who reacted. Does not gate visibility. */
    @Column(name = "User_ID", nullable = false)
    private String userId;

    @Column(name = "Emoji", nullable = false)
    private String emoji;
}
