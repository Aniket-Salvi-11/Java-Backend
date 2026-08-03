package com.closemore.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Inbound body for POST /api/v1/deals.
 *
 * <p>dealId is absent: the server generates it. Letting a client choose a primary key invites
 * collisions and lets one tenant probe another's id space by watching which inserts fail.
 *
 * <p><b>status is absent too, and that is the more interesting omission.</b> It is derived from the
 * stage by DealStageRules, never accepted from the caller. A client that could set status
 * independently of stage could create a deal sitting in "Negotiation" while reporting as Closed Won
 * - and nothing downstream would flag the contradiction, because every report trusts one column or
 * the other, not both.
 *
 * <p>The financial fields default to zero when omitted. dealValue is accepted here but is
 * recalculated from line items as soon as any exist - see DealService.
 */
public record DealCreateRequest(
        @NotBlank(message = "dealName is required") @Size(max = 300) String dealName,
        @NotBlank(message = "associatedContactId is required") String associatedContactId,
        @NotBlank(message = "pipelineId is required") String pipelineId,
        @NotBlank(message = "currentStage is required") @Size(max = 120) String currentStage,
        @PositiveOrZero(message = "dealValue cannot be negative") double dealValue,
        @NotBlank(message = "expectedCloseDate is required") @Size(max = 40) String expectedCloseDate,
        @Min(value = 0, message = "probabilityPercentage must be between 0 and 100")
        @Max(value = 100, message = "probabilityPercentage must be between 0 and 100")
        int probabilityPercentage,
        @Size(max = 200) String dealSource,
        String ownerId,
        @PositiveOrZero double arr,
        @PositiveOrZero double tcv,
        @PositiveOrZero double tlv,
        @PositiveOrZero double commission,
        @PositiveOrZero double partnerCommission,
        @PositiveOrZero double distributorCommission,
        List<String> teamMemberIds
) {
}
