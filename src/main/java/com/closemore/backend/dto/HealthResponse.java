package com.closemore.backend.dto;

/**
 * GET /api/health.
 *
 * <p>{@code database} is separate from {@code status} deliberately: an application that is running
 * but cannot reach Postgres is not healthy, and a check that only reported process liveness would
 * keep a broken instance in the load balancer while every request returned 500.
 */
public record HealthResponse(
        String status,
        String database) {
}
