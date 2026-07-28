package com.closemore.backend.dto;

import java.time.OffsetDateTime;

/** Outbound DTO for a contact. See UserResponse for the entity-never-serialized rationale. */
public record ContactResponse(
        String contactId,
        String firstName,
        String lastName,
        String email,
        String phonePrimary,
        String organizationName,
        String contactType,
        String source,
        String createdDate,
        String ownerId,
        String avatarDataUrl,
        String orgAvatarDataUrl,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
