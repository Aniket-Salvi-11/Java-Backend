package com.closemore.backend.dto;

import java.time.OffsetDateTime;

/**
 * Outbound DTO for an audit log entry.
 *
 * <p>beforeState and afterState are serialised record snapshots and the most sensitive payload in
 * the schema. Consider whether an endpoint really needs them before returning this DTO whole - a
 * summary view listing who did what to which object is usually enough, and leaking a full
 * before/after pair discloses field values the caller may not otherwise be able to read.
 */
public record EventLogResponse(
        Integer logEntryId,
        OffsetDateTime timestamp,
        String userId,
        String userName,
        String actionType,
        String objectType,
        String objectId,
        String objectName,
        String beforeState,
        String afterState
) {
}
