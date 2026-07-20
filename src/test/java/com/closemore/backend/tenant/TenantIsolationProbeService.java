package com.closemore.backend.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only. Exercises the real `contacts` table (RLS-protected per V3__rls.sql /
 * V4__tenant_rls.sql) through the same @Transactional -> TenantContextAspect -> JdbcTemplate
 * path production services will use, so a passing TenantIsolationIT is actually evidence the
 * wiring works end to end - not just that the echo diagnostic's session variables round-trip.
 */
@Service
@RequiredArgsConstructor
public class TenantIsolationProbeService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public int countVisibleContacts() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class);
        return count == null ? 0 : count;
    }
}
