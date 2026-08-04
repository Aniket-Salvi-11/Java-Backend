package com.closemore.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/users - an Admin creating a user directly, as opposed to self-service signup.
 *
 * <p>No {@code organizationName}: it is taken from the authenticated Admin, never from the body.
 * Accepting it would let an Admin create users in another tenant, and the users policy WITH CHECK
 * would refuse the insert anyway - so the only thing a body field could produce is a confusing 500
 * instead of a clear rule.
 *
 * <p>No {@code password} either. An Admin-created account has no credential until its owner sets
 * one; there is no route in v5's inventory that sets another user's password, and inventing one
 * here would be scope this migration has not agreed.
 */
public record UserCreateRequest(
        @NotBlank(message = "firstName is required") String firstName,
        @NotBlank(message = "lastName is required") String lastName,
        @NotBlank(message = "email is required") @Email(message = "email must be a valid address") String email,
        @NotBlank(message = "role is required") String role,
        String status,
        String phoneNumber,
        String residentialAddress,
        String officeAddress) {
}
