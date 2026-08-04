package com.closemore.backend.dto;

/**
 * The safe user projection - everything about a user EXCEPT password material.
 *
 * <p>Neither {@code Password} nor {@code Password_Hash} appears here and neither ever should. This
 * is the only shape in which a user leaves the Users API, so the omission is the enforcement: there
 * is no code path that could serialise a hash by accident, because the field does not exist on the
 * type. AuthenticatedUserRow is a deliberately separate type for the login path, which does need
 * the hash - see its javadoc.
 */
public record UserResponse(
        String userId,
        String firstName,
        String lastName,
        String email,
        String role,
        String status,
        String organizationName,
        String phoneNumber,
        String avatarDataUrl,
        String residentialAddress,
        String officeAddress,
        String createdAt,
        String updatedAt) {
}
