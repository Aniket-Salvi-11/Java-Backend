package com.closemore.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Inbound body for PUT /api/v1/deals/{id} - name, value, dates, owner, team and financials.
 *
 * <p>A full replace, not a patch: every required field must be present. The Next.js backend exposes
 * no PATCH and this port does not add one.
 *
 * <p><b>currentStage is deliberately not here.</b> Moving a deal between stages has side effects -
 * status, probability, and the won/lost determination - so it belongs to PUT /stage and PUT /lost,
 * which apply them consistently. Allowing a stage change through the generic update as well would
 * create a second path that skips those rules, which is precisely how a deal ends up in "Closed Won"
 * with status "Open".
 *
 * <p><b>teamMemberIds replaces the whole team when supplied, and leaves it untouched when null.</b>
 * Those are genuinely different requests: an empty list means "remove everyone", null means "I am
 * not editing the team". Treating the two the same would silently strip a deal's team on every
 * update sent by a client that does not know the field exists.
 *
 * <p>ownerId works the same way - null leaves ownership alone. Reassignment is restricted to Admins
 * in the service.
 */
public record DealUpdateRequest(
        @NotBlank(message = "dealName is required") @Size(max = 300) String dealName,
        @NotBlank(message = "associatedContactId is required") String associatedContactId,
        @NotBlank(message = "pipelineId is required") String pipelineId,
        @PositiveOrZero(message = "dealValue cannot be negative") double dealValue,
        @NotBlank(message = "expectedCloseDate is required") @Size(max = 40) String expectedCloseDate,
        @Min(value = 0, message = "probabilityPercentage must be between 0 and 100")
        @Max(value = 100, message = "probabilityPercentage must be between 0 and 100")
        int probabilityPercentage,
        @Size(max = 200) String dealSource,
        @Size(max = 1000) String winLossReason,
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
