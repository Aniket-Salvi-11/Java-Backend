package com.closemore.backend.dto;

/** Outbound DTO for deal team membership. The composite key is flattened for the wire. */
public record DealTeamMemberResponse(
        String dealId,
        String userId
) {
}
