package com.closemore.backend.dto;

import java.util.List;

/**
 * The body for GET /api/v1/deals/{id}/story - the audit timeline for one deal.
 *
 * <p>Two sources, deliberately kept apart rather than merged into one sorted stream. Audit entries
 * are system-generated and describe field changes; activities are human-written notes, calls and
 * meetings. They have different shapes, different timestamp columns (one a real timestamp, one a
 * text date), and different meanings to the reader. Interleaving them here would force a lossy
 * common shape and put the ordering decision in the backend, where it cannot be changed per screen.
 *
 * <p>The plan's description of this endpoint also mentions tasks. Tasks have no reference to a deal
 * anywhere in the schema, so there is nothing to join on - see DealDetailResponse.
 */
public record DealStoryResponse(
        String dealId,
        List<EventLogResponse> auditTrail,
        List<ActivityResponse> activities
) {
}
