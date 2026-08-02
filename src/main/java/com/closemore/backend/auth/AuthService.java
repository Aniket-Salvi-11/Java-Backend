package com.closemore.backend.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Orchestrates the three session operations. {@link LoginService} answers "are these credentials
 * valid"; this class turns that answer into tokens, renews them, and ends sessions.
 *
 * <p>Kept separate from LoginService so that credential verification stays independently testable
 * and so a future Google sign-in path can reach {@link #issueTokensFor} without going near password
 * handling - proving identity and issuing a session are genuinely different jobs, and that
 * separation is what makes adding another identity provider a new entry point rather than a
 * redesign.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String LOOKUP_BY_ID_SQL = """
            SELECT "User_ID", "First_Name", "Last_Name", "Email", "Role", "Status",
                   "Organization_Name", "Phone_Number", "Residential_Address", "Office_Address",
                   "Created_At", "Updated_At"
            FROM auth_lookup_user_by_id(?)
            """;

    /** Password columns are absent from the by-id lookup, so both are null here by construction. */
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
            rs.getObject("Created_At", OffsetDateTime.class),
            rs.getObject("Updated_At", OffsetDateTime.class),
            null,
            null);

    private final LoginService loginService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Verifies credentials and opens a session.
     *
     * @throws AuthenticationException 400/401/403 exactly as {@link LoginService#authenticate}
     */
    public LoginResult login(String email, String rawPassword) {
        AuthenticatedUserRow user = loginService.authenticate(email, rawPassword);
        return new LoginResult(issueTokensFor(user), loginService.toResponse(user));
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * <p>The old token is consumed in the process - see RefreshTokenService for why rotation
     * matters. The user is then re-read from the database rather than trusted from the old token,
     * so a deactivated account stops renewing and a role change takes effect here.
     *
     * @throws AuthenticationException 401 if the token is unusable, 403 if the account may no
     *                                 longer sign in
     */
    public LoginResult refresh(String rawRefreshToken) {
        String userId = refreshTokenService.consume(rawRefreshToken);
        if (userId == null) {
            throw new AuthenticationException(401, "Invalid or expired session");
        }

        AuthenticatedUserRow user = lookupById(userId);
        if (user == null) {
            // The row was deleted while a session was live. The token is already consumed, so
            // nothing further is needed - there is simply nobody to issue a token for.
            log.warn("Refresh presented for user {} which no longer exists", userId);
            throw new AuthenticationException(401, "Invalid or expired session");
        }

        requireStillAllowed(user);

        return new LoginResult(issueTokensFor(user), loginService.toResponse(user));
    }

    /**
     * Ends one session. Idempotent, and deliberately reports success either way - whether a given
     * token was live is not information a caller should be able to probe for.
     */
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    /**
     * Ends every session for a user. This is the lever for "deactivate this account" and "this
     * device was lost"; the user keeps working for at most one access-token lifetime after it.
     *
     * @return how many sessions were ended
     */
    public int logoutEverywhere(String userId) {
        return refreshTokenService.revokeAllFor(userId);
    }

    /**
     * Mints an access token and a refresh token for an already-authenticated user.
     *
     * <p>Public because identity can be proven in more than one way. A Google sign-in path would
     * resolve the Google account to a users row and call this - nothing below it needs to know
     * which route was taken.
     */
    public IssuedTokens issueTokensFor(AuthenticatedUserRow user) {
        AccessToken access = jwtService.issue(user);
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(user.userId());

        return new IssuedTokens(
                access.value(), access.expiresAt(),
                refresh.value(), refresh.expiresAt());
    }

    private AuthenticatedUserRow lookupById(String userId) {
        List<AuthenticatedUserRow> rows = jdbcTemplate.query(LOOKUP_BY_ID_SQL, ROW_MAPPER, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Same rules as login, applied again at refresh. Statuses are documented in V6.
     *
     * <p>Note this deliberately re-checks rather than trusting that the user was Active when the
     * session started. An account deactivated an hour ago must not be able to renew itself for the
     * remaining six days of its refresh token.
     */
    private void requireStillAllowed(AuthenticatedUserRow user) {
        if ("Active".equals(user.status())) {
            return;
        }
        if ("Pending_Approval".equals(user.status())) {
            throw new AuthenticationException(403,
                    "Your account is pending System Administrator approval.");
        }
        throw new AuthenticationException(403, "Account is inactive");
    }

    /** Tokens plus the safeUser body the API returns. */
    public record LoginResult(IssuedTokens tokens, com.closemore.backend.dto.UserResponse user) {
    }
}
