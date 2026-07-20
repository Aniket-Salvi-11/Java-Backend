package com.closemore.backend.context;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
