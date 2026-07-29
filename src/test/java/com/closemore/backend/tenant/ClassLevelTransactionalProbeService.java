package com.closemore.backend.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only, and the entire point of it is the placement of @Transactional: on the CLASS, not on
 * the method. This is the idiomatic way a Phase 3 service will be written
 * (`@Service @Transactional public class DealService`), and the original
 * `@annotation(...Transactional)` pointcut did not match it - meaning every method on such a
 * service would have run with no RLS session variables at all.
 *
 * If someone narrows TenantContextAspect's pointcut back to @annotation only, the assertions
 * against this bean in TenantIsolationIT fail. That is the regression guard.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ClassLevelTransactionalProbeService {

    private final JdbcTemplate jdbcTemplate;

    public int countVisibleContacts() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contacts", Integer.class);
        return count == null ? 0 : count;
    }
}
