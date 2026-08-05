package com.closemore.backend.dto;

/**
 * GET /api/v1/dashboard/revenue - closed-won against closed-lost.
 *
 * <p>Counts travel with the values on purpose. A total of zero is ambiguous on its own: no closed
 * deals and closed deals worth nothing look identical, and a dashboard that cannot tell them apart
 * will render "no data" over a real result.
 */
public record RevenueSummaryResponse(
        long wonCount,
        double wonValue,
        long lostCount,
        double lostValue) {
}
