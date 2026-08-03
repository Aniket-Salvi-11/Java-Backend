package com.closemore.backend.service;

import com.closemore.backend.domain.EventLogEntity;
import com.closemore.backend.domain.UserEntity;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.repository.EventLogRepository;
import com.closemore.backend.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Writes audit entries to {@code events_log}. The port of the Next.js backend's event writer, and a
 * prerequisite for every write endpoint in Phase 3 - deals, contacts, activities and users all log.
 *
 * <p><b>No {@code @Transactional} on this class, deliberately.</b> Every method here is called from
 * inside a caller's transaction, and it must stay that way. If the audit write had its own
 * transaction, a business write could commit while its audit entry rolled back, or the reverse - and
 * an audit log that disagrees with the data it describes is worse than no audit log, because people
 * trust it. Joining the caller's transaction means the row and its audit entry commit together or
 * not at all. It also means the tenant session variables the caller's aspect established are already
 * in force, so the {@code events_log} write-side policy added in V10 passes.
 *
 * <p><b>The user name lookup.</b> {@code User_Name} is NOT NULL in the schema, and the JWT carries
 * only id, role and tenant - not a display name. So the name is read back from {@code users}, which
 * costs one indexed primary-key lookup per write and stays inside the caller's transaction, so RLS
 * applies. Adding the name to the token would avoid the query, but names change and tokens live for
 * thirty minutes, which would put a stale name in a permanent audit record.
 *
 * <p>The fallback matters more than it looks: if that lookup somehow returns nothing, the audit entry
 * is still written with the user id in place of the name. Failing to write an audit row because a
 * cosmetic field could not be resolved would abort the business operation attached to it.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final EventLogRepository eventLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /** A create: no before-state exists. */
    public void logCreate(AuthenticatedUser user, String objectType, String objectId,
                          String objectName, Object afterState) {
        write(user, "CREATE", objectType, objectId, objectName, null, afterState);
    }

    /** An update: both states recorded, so the diff is reconstructable without the current row. */
    public void logUpdate(AuthenticatedUser user, String objectType, String objectId,
                          String objectName, Object beforeState, Object afterState) {
        write(user, "UPDATE", objectType, objectId, objectName, beforeState, afterState);
    }

    /**
     * A delete: the before-state is the only surviving copy of the row, which is the single most
     * important thing this table stores.
     */
    public void logDelete(AuthenticatedUser user, String objectType, String objectId,
                          String objectName, Object beforeState) {
        write(user, "DELETE", objectType, objectId, objectName, beforeState, null);
    }

    private void write(AuthenticatedUser user, String actionType, String objectType,
                       String objectId, String objectName, Object before, Object after) {
        EventLogEntity entry = new EventLogEntity();
        entry.setUserId(user.userId());
        entry.setUserName(displayName(user.userId()));
        entry.setActionType(actionType);
        entry.setObjectType(objectType);
        entry.setObjectId(objectId);
        entry.setObjectName(objectName);
        entry.setBeforeState(toJson(before));
        entry.setAfterState(toJson(after));

        eventLogRepository.save(entry);
    }

    private String displayName(String userId) {
        return userRepository.findById(userId)
                .map(this::fullName)
                .orElse(userId);
    }

    private String fullName(UserEntity user) {
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }

    /**
     * Serialises a state snapshot. Null in, null out - the column is nullable precisely so that a
     * create has no before-state and a delete has no after-state.
     *
     * <p>A serialisation failure is swallowed into a marker string rather than thrown. The same
     * reasoning as the name fallback: this method runs inside someone else's transaction, and an
     * unserialisable field is not a reason to roll back the business operation it is describing.
     */
    private String toJson(Object state) {
        if (state == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"state could not be serialised\"}";
        }
    }
}
