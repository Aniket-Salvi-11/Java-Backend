package com.closemore.backend.dto;

/** Outbound DTO for a deal/contact link. The composite key is flattened for the wire. */
public record DealContactResponse(
        String dealId,
        String contactId,
        boolean primary
) {
}
