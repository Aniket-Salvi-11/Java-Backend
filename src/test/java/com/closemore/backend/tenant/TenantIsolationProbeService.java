package com.closemore.backend.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
