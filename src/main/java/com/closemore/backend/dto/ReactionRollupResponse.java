package com.closemore.backend.dto;

/**
 * One emoji on one comment, with how many people used it and whether the caller is among them.
 *
 * <p><b>A rollup rather than the raw reaction rows.</b> The screen shows "thumbs-up 3" with the
 * button highlighted if you are one of the three; sending every individual row would make the client
 * do that grouping, and every client would have to do it identically. It would also send the user id
 * of everyone who reacted to everyone who can read the comment, which is more than the interface
 * needs.
 *
 * <p>reactedByCurrentUser is what lets the client render the toggle state without a second lookup,
 * and it is per-caller: the same comment produces different values for different readers.
 */
public record ReactionRollupResponse(
        String emoji,
        int count,
        boolean reactedByCurrentUser
) {
}
