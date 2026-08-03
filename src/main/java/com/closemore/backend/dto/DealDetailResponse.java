package com.closemore.backend.dto;

import java.util.List;

/**
 * The composite body for GET /api/v1/deals/{id} - the deal plus everything hanging off it.
 *
 * <p><b>Why one composite rather than five round trips.</b> The deal detail screen needs all of this
 * at once, and fetching it separately would mean five requests, five tenant-context set-ups, and a
 * window in which the parts disagree because something changed between calls. Assembled inside one
 * transaction, every list here is consistent with the deal as it stood at that moment.
 *
 * <p>Attachments are the activity attachments belonging to this deal's activities, flattened. They
 * reach the deal through the activity that carries them, so a deal with no activities has none.
 *
 * <p>Note the plan's description of this endpoint mentions tasks as well. Tasks carry no reference
 * to a deal in the schema - no column, no join table - so they cannot be included without a schema
 * change. Recorded as an open item rather than quietly dropped.
 */
public record DealDetailResponse(
        DealResponse deal,
        List<LineItemResponse> lineItems,
        List<DealContactResponse> contacts,
        List<DealTeamMemberResponse> team,
        List<ActivityResponse> activities,
        List<ActivityAttachmentResponse> attachments
) {
}
