package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for POST /api/v1/tasks/{taskId}/comments/{commentId}/reactions.
 *
 * <p>The endpoint toggles rather than adds, so there is no separate delete: sending the same emoji
 * twice removes it. That matches how every chat client behaves and means the client does not have to
 * track whether it already reacted.
 *
 * <p>The length cap is 16 rather than 1 or 2. An emoji is not one character - a family emoji with
 * skin-tone modifiers is a sequence of code points joined by zero-width joiners, and capping at
 * "a couple of chars" silently rejects exactly the emoji people care about.
 */
public record ReactionRequest(
        @NotBlank(message = "emoji is required") @Size(max = 16, message = "emoji is too long")
        String emoji
) {
}
