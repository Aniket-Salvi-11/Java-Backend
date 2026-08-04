package com.closemore.backend.auth;

/**
 * Body of POST /api/auth/signup.
 *
 * <p><b>No tokens here, deliberately.</b> Signup can produce an account in Pending_Approval, and
 * issuing a session for an account that may not sign in would contradict the Status check
 * LoginService already enforces. Rather than return tokens sometimes and not others - two response
 * shapes for one endpoint, the exact silent-contract-drift risk Section 11 of the plan warns about
 * - this always returns the account and never a session. An Active user logs in as normal
 * immediately afterwards.
 *
 * <p>CONFIRM WITH THE FRONTEND TEAM: if the JS signup screen expects to be logged in on success,
 * it needs one extra call. Raised in docs/HANDOFF.md.
 */
public record SignupResponse(
        String User_ID,
        String First_Name,
        String Last_Name,
        String Email,
        String Role,
        String Status,
        String Organization_Name) {
}
