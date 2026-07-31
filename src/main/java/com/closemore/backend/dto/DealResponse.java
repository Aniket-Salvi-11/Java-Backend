package com.closemore.backend.dto;

import java.time.OffsetDateTime;

/** Outbound DTO for a deal. See UserResponse for the entity-never-serialized rationale. */
public record DealResponse(
        String dealId,
        String dealName,
        String associatedContactId,
        String pipelineId,
        String currentStage,
        double dealValue,
        String expectedCloseDate,
        int probabilityPercentage,
        String winLossReason,
        String ownerId,
        String status,
        String dealSource,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        double arr,
        double tcv,
        double tlv,
        double commission,
        double partnerCommission,
        double distributorCommission
) {
}
