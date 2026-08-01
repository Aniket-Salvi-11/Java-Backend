package com.closemore.backend.dto;

/** Outbound DTO for a comment reaction. */
public record CommentReactionResponse(
        String reactionId,
        String commentId,
        String userId,
        String emoji
) {
}
