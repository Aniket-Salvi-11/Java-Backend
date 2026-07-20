package com.closemore.backend.context;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Not part of the ported application logic - a small diagnostic used to prove, end to end,
 * that TenantContextAspect is actually firing before this method's query runs. Reads back
 * the three session variables Postgres currently has set via current_setting(...), which
 * only returns non-empty values if set_config ran on this exact connection/transaction.
 *
 * Wire GET /internal/tenant-context-echo (see TenantContextEchoController) to this during
 * Phase 0 to sanity-check the AOP wiring manually before writing TenantIsolationIT, then
 * feel free to delete both once that test is green.
 */
@Service
@RequiredArgsConstructor
public class TenantContextEchoService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public TenantEcho readCurrentSessionVariables() {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    current_setting('app.current_user_id', true)     AS user_id,
                    current_setting('app.current_user_role', true)   AS role,
                    current_setting('app.current_user_tenant', true) AS tenant
                """,
                (rs, rowNum) -> new TenantEcho(
                        rs.getString("user_id"),
                        rs.getString("role"),
                        rs.getString("tenant")
                ));
    }

    public record TenantEcho(String userId, String role, String tenant) {
    }
}
