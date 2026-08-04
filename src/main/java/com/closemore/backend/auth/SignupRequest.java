package com.closemore.backend.auth;

/**
 * Body of POST /api/auth/signup.
 *
 * <p>Capitalised field names, matching LoginRequest and the shape the existing frontend already
 * sends. See the note in LoginRequest: these are the JS backend's property names and Section 3 of
 * the migration plan commits to not changing the contract.
 *
 * <p>{@code Role} is a REQUEST, not an instruction. auth_signup decides the role and status that
 * are actually stored; a caller asking for Admin in an established organisation gets
 * Pending_Approval, and the very first caller in a new organisation gets Admin whatever they asked
 * for. Null is treated as Sales_Rep.
 */
public record SignupRequest(
        String First_Name,
        String Last_Name,
        String Email,
        String Password,
        String Organization_Name,
        String Role) {
}
