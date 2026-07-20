package com.closemore.backend.context;

/**
 * Java equivalent of the payload stored in AsyncLocalStorage by the original
 * src/lib/db.ts (see reference doc, Section 5). Populated once per request by
 * {@link com.closemore.backend.filter.UserContextFilter} immediately after
 * authentication succeeds, and read by {@link com.closemore.backend.aop.TenantContextAspect}
 * to issue the three set_config calls.
 *
 * userId / role / organizationName map 1:1 onto:
 *   app.current_user_id, app.current_user_role, app.current_user_tenant
 *
 * NOTE: organizationName may be null/blank for edge cases (see 004_tenant_rls.sql) -
 * callers must pass an empty string, never null, into set_config (Postgres text params
 * cannot be null for this call the way the code is written) - matches
 * `user.Organization_Name || ''` in db.ts exactly.
 */
public record RequestUserContext(
        String userId,
        String role,
        String organizationName
) {
    public String organizationNameOrEmpty() {
        return organizationName == null ? "" : organizationName;
    }
}
