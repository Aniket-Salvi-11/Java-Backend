package com.closemore.backend.auth;

import java.time.OffsetDateTime;

/**
 * One row from auth_lookup_user_by_email(). Deliberately a separate type from UserEntity rather
 * than reusing it.
 *
 * <p>The reason is that this record carries password material, and it exists only inside the login
 * transaction. Loading a managed UserEntity here would put a plaintext password and a bcrypt hash
 * into the persistence context, where a stray flush could write them somewhere unintended, and
 * would blur the line between "a candidate row we are still verifying" and "an authenticated
 * user". Nothing outside {@link LoginService} should ever hold one of these.
 *
 * @param password     plaintext, legacy. Null for users created after the JS backend is retired.
 * @param passwordHash bcrypt digest. Null means this user has not yet been migrated.
 */
public record AuthenticatedUserRow(
        String userId,
        String firstName,
        String lastName,
        String email,
        String role,
        String status,
        String organizationName,
        String phoneNumber,
        String residentialAddress,
        String officeAddress,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String password,
        String passwordHash
) {
}
