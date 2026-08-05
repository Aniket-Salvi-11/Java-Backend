package com.closemore.backend.service;

import com.closemore.backend.domain.EventLogEntity;
import com.closemore.backend.dto.EventLogResponse;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.rbac.Role;
import com.closemore.backend.repository.EventLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The audit log reader - one endpoint.
 *
 * <p><b>A hard cap of 500 rows, not pages.</b> That is v5's description and it is deliberately NOT
 * the project's bare-array/envelope convention. The reasoning holds: this is an investigation
 * surface, not a browsing one, and paging an audit log invites a client to walk it - which for a
 * table that grows on every write in the system is a very expensive way to read something nobody
 * scrolls to the end of. If the log ever needs real navigation, it needs filters (by object, by
 * user, by date), not pages.
 *
 * <p>Tenant scoping is RLS's, as everywhere else: events_log carries the acting user's
 * organisation and the policy matches it against the session context. The Admin check below is
 * defence in depth on top of that, not the only guard - unlike products and pipelines.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminService {

    /** v5: "most recent 500". */
    private static final int MAX_ENTRIES = 500;

    private final EventLogRepository eventLogRepository;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;

    /** GET /api/v1/admin/events-log - Admin only. */
    public List<EventLogResponse> recentEvents() {
        AuthenticatedUser user = currentUserService.require();
        rbacService.requireRole(user, Role.ADMIN);

        // Sorted by timestamp descending and capped in the database, so the 500 rows returned are
        // the most recent 500 rather than an arbitrary 500 that happen to come back first.
        List<EventLogEntity> entries = eventLogRepository.findAll(
                        PageRequest.of(0, MAX_ENTRIES,
                                Sort.by(Sort.Direction.DESC, "timestamp", "logEntryId")))
                .getContent();

        return entries.stream().map(DtoMapper::toEventLogResponse).toList();
    }
}
