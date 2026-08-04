package com.closemore.backend.dto;

import jakarta.validation.constraints.Email;

/**
 * PUT /api/v1/users/{userId}.
 *
 * <p>Every field is nullable and null means "leave alone" - the same partial-update convention the
 * other groups use.
 *
 * <p><b>The authorisation rule is per FIELD, not per endpoint.</b> v5: "Update own profile, or any
 * user if Admin; only Admin can change email/role/status." So this one request type carries two
 * privilege levels, and UserService checks them field by field. A Sales_Rep sending
 * {@code {"phoneNumber": "..."}} for themselves succeeds; the same user sending
 * {@code {"role": "Admin"}} is refused even though the endpoint and the target row are identical.
 */
public record UserUpdateRequest(
        String firstName,
        String lastName,
        @Email(message = "email must be a valid address") String email,
        String role,
        String status,
        String phoneNumber,
        String avatarDataUrl,
        String residentialAddress,
        String officeAddress) {
}
