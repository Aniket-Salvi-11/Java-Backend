package com.closemore.backend.rbac;

/**
 * What rbac.ts's functions actually read off `user`: User_ID and Role. Deliberately not the
 * full JPA User entity (that doesn't exist until Phase 1, step 8) so RBAC logic can be ported
 * and unit tested in Phase 0 without a dependency on the entity layer.
 */
public record AuthenticatedUser(String userId, Role role) {
}
