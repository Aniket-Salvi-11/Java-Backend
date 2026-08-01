package com.closemore.backend.tenant;

import com.closemore.backend.domain.EventLogEntity;
import com.closemore.backend.repository.EventLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Test-only. Note the deliberate split: reads are readOnly, but {@link #writeAuditEntry} opens a
 * read-write transaction because it is the one place the suite exercises Hibernate's IDENTITY
 * generation - the only auto-generated key in the schema.
 */
@Service
@RequiredArgsConstructor
public class EventLogReadService {

    private final EventLogRepository events;

    @Transactional(readOnly = true)
    public List<EventLogEntity> allEntries() {
        return events.findAll();
    }

    @Transactional(readOnly = true)
    public List<EventLogEntity> historyFor(String objectType, String objectId) {
        return events.findByObjectTypeAndObjectId(objectType, objectId);
    }

    /** Read-write on purpose: proves @GeneratedValue(IDENTITY) round-trips against the SERIAL. */
    @Transactional
    public EventLogEntity writeAuditEntry(EventLogEntity entry) {
        return events.saveAndFlush(entry);
    }
}
