package com.closemore.backend.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A comment with its emoji reaction rollups - the body of GET /api/v1/tasks/{taskId}/comments.
 *
 * <p>Flat comment fields plus one added key, for the same reason as TaskWithNotificationsResponse:
 * additive against the Next.js shape, so a client that does not know about reactions ignores them
 * rather than failing to deserialise.
 *
 * <p>Assembled in one transaction with a single query for all the reactions on all the comments,
 * rather than one query per comment. A task with forty comments would otherwise issue forty-one
 * queries for one screen - the classic N+1, and the reason CommentReactionRepository has a
 * findByCommentIdIn.
 */
public record TaskCommentWithReactionsResponse(
        String commentId,
        String taskId,
        String userId,
        String userName,
        String content,
        String attachmentUrl,
        OffsetDateTime createdAt,
        List<ReactionRollupResponse> reactions
) {

    public static TaskCommentWithReactionsResponse of(TaskCommentResponse comment,
                                                      List<ReactionRollupResponse> reactions) {
        return new TaskCommentWithReactionsResponse(
                comment.commentId(), comment.taskId(), comment.userId(), comment.userName(),
                comment.content(), comment.attachmentUrl(), comment.createdAt(), reactions);
    }
}
