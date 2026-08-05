package com.closemore.backend.dto;

/**
 * One row of GET /api/v1/dashboard/leaderboard - reps ranked by won revenue.
 *
 * <p>{@code ownerName} is resolved from the users table rather than stored on the deal. A rep who
 * has left the organisation still owns their closed deals, and a leaderboard that showed a raw id
 * for them would look like a bug.
 */
public record LeaderboardEntryResponse(
        String ownerId,
        String ownerName,
        long wonCount,
        double wonValue) {
}
