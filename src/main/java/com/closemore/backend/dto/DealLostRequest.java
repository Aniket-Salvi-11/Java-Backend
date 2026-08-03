package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound body for PUT /api/v1/deals/{id}/lost.
 *
 * <p>The reason is required, not optional. A lost deal with no recorded reason is the one audit
 * record nobody can reconstruct later, and "why did we lose it" is the entire analytical value of
 * marking it lost rather than deleting it.
 */
public record DealLostRequest(
        @NotBlank(message = "winLossReason is required") @Size(max = 1000) String winLossReason
) {
}
