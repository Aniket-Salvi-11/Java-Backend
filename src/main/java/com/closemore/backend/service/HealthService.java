package com.closemore.backend.service;

import com.closemore.backend.dto.HealthResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The liveness check.
 *
 * <p><b>No {@code @Transactional}, and JdbcTemplate rather than a repository.</b> This runs with no
 * authentication and therefore no tenant context, so every RLS-guarded finder would return nothing
 * - and a health check built on one would report the database as unreachable the moment it worked
 * correctly. {@code SELECT 1} touches no table and no policy.
 *
 * <p><b>It really does hit the database</b>, as v5 specifies. A check that only proved the process
 * was running would keep an instance in the load balancer while every request returned 500, which
 * is the failure a health check exists to prevent.
 */
@Service
@RequiredArgsConstructor
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final JdbcTemplate jdbcTemplate;

    /** True when Postgres answers. Never throws - the caller needs a status, not a stack trace. */
    public HealthResponse check() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new HealthResponse("UP", "UP");
        } catch (Exception unreachable) {
            // Logged at warn rather than error: an orchestrator polling this will produce one line
            // per interval, and a genuinely down database is already loud elsewhere.
            log.warn("Health check could not reach the database: {}", unreachable.getMessage());
            return new HealthResponse("DOWN", "DOWN");
        }
    }
}
