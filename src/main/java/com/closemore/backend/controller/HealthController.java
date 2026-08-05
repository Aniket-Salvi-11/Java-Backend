package com.closemore.backend.controller;

import com.closemore.backend.dto.HealthResponse;
import com.closemore.backend.service.HealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness check. Unauthenticated, per v5.
 *
 * <p><b>Path is /api/health, NOT /api/v1/health.</b> The versioning decision covers resource
 * groups; this is infrastructure. A load balancer or orchestrator health probe is configured once
 * and outlives API versions, and moving it later would mean a deployment change rather than a
 * client change. v5 also inventories it unversioned.
 *
 * <p>The path must stay in {@code JwtAuthenticationFilter.PUBLIC_PATH_PREFIXES}. A health check
 * that demands a bearer token is useless to the thing that needs it, and the failure mode is
 * unpleasant: every instance reports unhealthy and the whole service is pulled out of rotation.
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    /**
     * GET /api/health
     *
     * <p>200 when the database answers, 503 when it does not. The status has to be in the HTTP code
     * and not only in the body, because that is the only part most probes read.
     */
    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        HealthResponse response = healthService.check();

        return "UP".equals(response.status())
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
}
