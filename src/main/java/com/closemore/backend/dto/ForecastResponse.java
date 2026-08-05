package com.closemore.backend.dto;

/**
 * GET /api/v1/dashboard/forecast - the open pipeline.
 *
 * <p>Two totals, not one. v5 says "open pipeline forecast revenue total" without saying whether
 * that is raw or probability-weighted, and the two differ by a lot on any real pipeline. Returning
 * both means the frontend can render whichever the existing dashboard shows without a second
 * round-trip, and it makes the ambiguity visible rather than silently picking one.
 *
 * @param weightedValue sum of {@code Deal_Value * Probability_Percentage / 100}
 */
public record ForecastResponse(
        long openCount,
        double openValue,
        double weightedValue) {
}
