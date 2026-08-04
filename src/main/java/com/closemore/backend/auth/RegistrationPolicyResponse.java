package com.closemore.backend.auth;

/**
 * Body of GET /api/auth/registration-policy.
 *
 * <p>Tells the signup form what kind of signup this would be, so it can adapt - v5's words. Three
 * fields rather than one enum because the form makes two separate decisions: whether to explain
 * that this account will own the new organisation, and whether to offer the privileged roles at
 * all.
 *
 * @param bootstrap       no users exist in this organisation; the next signup becomes its Admin
 * @param approvalPending a privileged role requested now would land in Pending_Approval
 * @param requiresApprover true when nobody could approve a privileged registration - an
 *                         established organisation whose only Admin is inactive. The form should
 *                         not offer roles that would create an account nobody can activate.
 */
public record RegistrationPolicyResponse(
        boolean bootstrap,
        boolean approvalPending,
        boolean requiresApprover) {

    static RegistrationPolicyResponse of(boolean bootstrap, boolean hasActiveAdmin) {
        return new RegistrationPolicyResponse(
                bootstrap,
                !bootstrap && hasActiveAdmin,
                !bootstrap && !hasActiveAdmin);
    }
}
