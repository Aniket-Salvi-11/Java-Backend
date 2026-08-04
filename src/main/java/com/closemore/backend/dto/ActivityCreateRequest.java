package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for POST /api/v1/activities.
 *
 * <p>logId is absent: the server generates it. loggedByUserId is absent too - it is always the
 * authenticated caller. Accepting it would let one user write history attributed to another, and the
 * activities RLS policy derives tenancy from exactly that column, so a forged value is also a
 * tenancy bypass rather than merely a lie in the audit trail.
 *
 * <p>parentObjectType is constrained to Deal or Contact in the service rather than by an annotation,
 * because the check has to match the RLS policy's own list and belongs next to the visibility check
 * it precedes.
 *
 * <p>logDate is optional and defaults to today. It is a TEXT column holding an ISO date, which is
 * why the range filters on the list endpoint work at all - see ActivitySpecifications.
 */
public record ActivityCreateRequest(
        @NotBlank(message = "parentObjectType is required") @Size(max = 40) String parentObjectType,
        @NotBlank(message = "parentObjectId is required") String parentObjectId,
        @NotBlank(message = "activityType is required") @Size(max = 80) String activityType,
        @NotBlank(message = "summary is required") @Size(max = 500) String summary,
        @NotBlank(message = "detailedDescription is required") String detailedDescription,
        @Size(max = 40) String followUpDate,
        @Size(max = 40) String logDate
) {
}
