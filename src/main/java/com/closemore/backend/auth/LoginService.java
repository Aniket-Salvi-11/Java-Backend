package com.closemore.backend.auth;

import com.closemore.backend.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Credential verification. Issues no token - that is Phase 2 batch 3; this class answers only
 * "are these credentials valid, and is this account allowed in".
 *
 * <p><b>Why JdbcTemplate and not a repository.</b> Login runs before any tenant context exists, so
 * every RLS policy on users evaluates to false and a Spring Data finder returns nothing. The two
 * SECURITY DEFINER functions from V11/V14 are the sanctioned way through that: each does exactly
 * one thing, takes an exact email or user id, and cannot enumerate the directory. Do not "simplify"
 * this by reintroducing a bypass in the policy - that reopens the hole V11 closed.
 *
 * <p><b>Why no @Transactional on the public method.</b> TenantContextAspect fires on transactional
 * methods and would try to set session variables from a RequestUserContext that does not exist yet.
 * The functions are self-contained and each runs in its own implicit transaction, so no wrapping
 * transaction is needed or wanted.
 *
 * <h2>The lazy password migration</h2>
 *
 * The live database stores plaintext passwords, and the legacy JS backend compares them with
 * {@code user.Password !== password}. Hashing that column in place would lock every migrated user
 * out of the live site, so V12 added Password_Hash alongside it and this class migrates users one
 * at a time as they log in:
 *
 * <pre>
 *   Password_Hash present -> verify against it. Done.
 *   Password_Hash null    -> verify against plaintext (what the JS backend does today),
 *                            and on success store the bcrypt hash.
 * </pre>
 *
 * Nobody is forced to reset, and the plaintext column is left untouched for the JS backend. It is
 * dropped in a later migration once that backend is retired.
 */
@Service
@RequiredArgsConstructor
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private static final String LOOKUP_SQL = """
            SELECT "User_ID", "First_Name", "Last_Name", "Email", "Role", "Status",
                   "Organization_Name", "Phone_Number", "Residential_Address", "Office_Address",
                   "Created_At", "Updated_At", "Password", "Password_Hash"
            FROM auth_lookup_user_by_email(?)
            """;

    private static final RowMapper<AuthenticatedUserRow> ROW_MAPPER = (rs, i) -> new AuthenticatedUserRow(
            rs.getString("User_ID"),
            rs.getString("First_Name"),
            rs.getString("Last_Name"),
            rs.getString("Email"),
            rs.getString("Role"),
            rs.getString("Status"),
            rs.getString("Organization_Name"),
            rs.getString("Phone_Number"),
            rs.getString("Residential_Address"),
            rs.getString("Office_Address"),
            rs.getObject("Created_At", java.time.OffsetDateTime.class),
            rs.getObject("Updated_At", java.time.OffsetDateTime.class),
            rs.getString("Password"),
            rs.getString("Password_Hash"));

    private final JdbcTemplate jdbcTemplate;
    private final PasswordService passwordService;

    /**
     * Verifies credentials and returns the authenticated user's row.
     *
     * <p>Returns the internal row type rather than a DTO because batch 3 needs Organization_Name
     * and Role from it to build the JWT claims that feed the RLS session variables. Use
     * {@link #toResponse} for anything that leaves the application.
     *
     * @throws AuthenticationException 400 for missing input, 401 for bad credentials, 403 for an
     *                                 account that exists but may not sign in
     */
    public AuthenticatedUserRow authenticate(String email, String rawPassword) {
        String normalisedEmail = email == null ? null : email.trim().toLowerCase();
        // Trimming the password matches the legacy JS backend, which does body.Password?.trim().
        // It matters: any existing password that was set with surrounding whitespace was STORED
        // trimmed, so it only verifies trimmed. Dropping this would lock those users out.
        String candidate = rawPassword == null ? null : rawPassword.trim();

        if (normalisedEmail == null || normalisedEmail.isEmpty()
                || candidate == null || candidate.isEmpty()) {
            throw new AuthenticationException(400, "Email and Password are required");
        }

        AuthenticatedUserRow user = lookup(normalisedEmail);
        if (user == null) {
            throw new AuthenticationException(401, "No account found with that email");
        }

        if (!verify(user, candidate)) {
            throw new AuthenticationException(401, "Incorrect password");
        }

        // Status gating happens AFTER the password check, matching the legacy backend. The order is
        // load-bearing: checking status first would let an attacker discover which accounts are
        // pending approval without knowing any password.
        requireSignInAllowed(user);

        return user;
    }

    /** Strips password material. This is the shape the API returns - the JS backend's safeUser. */
    public UserResponse toResponse(AuthenticatedUserRow user) {
        return new UserResponse(
                user.userId(),
                user.firstName(),
                user.lastName(),
                user.email(),
                user.role(),
                user.status(),
                user.phoneNumber(),
                user.organizationName(),
                user.residentialAddress(),
                user.officeAddress(),
                user.createdAt(),
                user.updatedAt());
    }

    private AuthenticatedUserRow lookup(String email) {
        List<AuthenticatedUserRow> rows = jdbcTemplate.query(LOOKUP_SQL, ROW_MAPPER, email);
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            // V13's unique index on lower("Email") makes this unreachable. Kept because before V13
            // the same lookup could match several rows, and the legacy backend hid that behind
            // LIMIT 1 - silently signing the user into whichever row Postgres returned first.
            // Failing loudly is the correct response to an ambiguous identity.
            log.error("Ambiguous login: {} rows matched one email address. V13's unique index "
                    + "should make this impossible - check it was applied.", rows.size());
            throw new AuthenticationException(500, "Account lookup was ambiguous");
        }
        return rows.get(0);
    }

    /**
     * Verifies the password, migrating the user from plaintext to bcrypt on the way through if this
     * is their first login against the Java backend.
     */
    private boolean verify(AuthenticatedUserRow user, String candidate) {
        if (user.passwordHash() != null && !user.passwordHash().isBlank()) {
            return passwordService.matches(candidate, user.passwordHash());
        }

        // Not yet migrated. Fall back to the legacy comparison. Deliberately an exact match on the
        // stored value, exactly as the JS backend does - anything looser would let people log in
        // with a password the legacy backend would have rejected.
        if (user.password() == null || !user.password().equals(candidate)) {
            return false;
        }

        migrateToHash(user, candidate);
        return true;
    }

    /**
     * Stores the bcrypt hash via auth_store_password_hash(). That function is used rather than a
     * plain UPDATE for two reasons: an UPDATE at this point has no tenant context and is silently
     * filtered to zero rows by RLS - reporting success while migrating nobody - and the function's
     * {@code Password_Hash IS NULL} guard makes the write single-shot, so two concurrent logins
     * cannot have one overwrite the other's hash.
     *
     * <p>A failure here is logged and swallowed. The user's password was correct, so refusing the
     * login because a background optimisation failed would be the wrong trade - they simply get
     * migrated on a later attempt.
     */
    private void migrateToHash(AuthenticatedUserRow user, String candidate) {
        try {
            Boolean migrated = jdbcTemplate.queryForObject(
                    "SELECT auth_store_password_hash(?, ?)",
                    Boolean.class,
                    user.userId(),
                    passwordService.hash(candidate));

            if (Boolean.TRUE.equals(migrated)) {
                log.info("Migrated stored password to bcrypt for user {}", user.userId());
            }
        } catch (RuntimeException ex) {
            log.warn("Could not migrate stored password for user {} - login still succeeds, "
                    + "will retry on next login", user.userId(), ex);
        }
    }

    /**
     * Statuses are documented in V6: Active | Inactive | Pending_Approval. The messages are
     * reproduced verbatim from the legacy backend because the frontend may match on them.
     */
    private void requireSignInAllowed(AuthenticatedUserRow user) {
        if ("Pending_Approval".equals(user.status())) {
            throw new AuthenticationException(403,
                    "Your account is pending System Administrator approval.");
        }
        if ("Inactive".equals(user.status())) {
            throw new AuthenticationException(403, "Account is inactive");
        }
        if (!"Active".equals(user.status())) {
            // Defensive: an unrecognised status should deny rather than default to allowing.
            log.warn("User {} has unrecognised status '{}' - denying sign-in",
                    user.userId(), user.status());
            throw new AuthenticationException(403, "Account is inactive");
        }
    }
}
