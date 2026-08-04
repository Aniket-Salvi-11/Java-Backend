package com.closemore.backend.dto;

import java.time.OffsetDateTime;

/**
 * Outbound DTO for a user. The Password field on UserEntity is deliberately absent here - entities
 * are never serialized directly (Migration Plan Section 3 principle, and the original's
 * `const { Password, ...safeUser } = user` in login/route.ts). This record IS that safeUser shape.
 *
 * Field names use the JSON the frontend already consumes. If the existing API returns PascalCase
 * keys, that is a parity question to confirm against real payloads in Phase 3 - this record uses
 * camelCase for now and is the single place to change if the contract says otherwise. Flagged, not
 * assumed.
 */
public record UserResponse(
        String userId,
        String firstName,
        String lastName,
        String email,
        String role,
        String status,
        String phoneNumber,
        String organizationName,
        String residentialAddress,
        String officeAddress,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
