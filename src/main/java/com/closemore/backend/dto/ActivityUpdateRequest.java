package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for PUT /api/v1/activities/{logId} - summary, description and follow-up date.
 *
 * <p>The plan describes this endpoint as updating exactly those three fields, and the omissions are
 * the interesting part. parentObjectType and parentObjectId are not editable: re-parenting an
 * activity would move it between deals, and since the RLS policy authorises an activity through its
 * parent, that is a way to hand a note to somebody who could not otherwise read it. activityType is
 * not editable either, because NOTE_CREATED has already fired for a Note and re-typing the row
 * afterwards would leave the pipeline holding a chunk for something that is no longer a note.
 *
 * <p>loggedByUserId and logDate stay as recorded. This is history.
 */
public record ActivityUpdateRequest(
        @NotBlank(message = "summary is required") @Size(max = 500) String summary,
        @NotBlank(message = "detailedDescription is required") String detailedDescription,
        @Size(max = 40) String followUpDate
) {
}
