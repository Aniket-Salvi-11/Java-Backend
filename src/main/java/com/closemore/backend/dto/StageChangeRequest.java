package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for PUT /api/v1/deals/{id}/stage.
 *
 * <p>winLossReason is optional and only meaningful when the target stage is terminal. It is accepted
 * here so that moving a deal straight to Closed Lost through this endpoint can carry its reason,
 * rather than forcing a second call to PUT /lost.
 */
public record StageChangeRequest(
        @NotBlank(message = "currentStage is required") @Size(max = 120) String currentStage,
        @Size(max = 1000) String winLossReason
) {
}
