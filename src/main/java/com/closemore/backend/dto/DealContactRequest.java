package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;

/** Inbound body for POST /api/v1/deals/{id}/contacts - attaches an existing contact to a deal. */
public record DealContactRequest(
        @NotBlank(message = "contactId is required") String contactId,
        boolean primary
) {
}
