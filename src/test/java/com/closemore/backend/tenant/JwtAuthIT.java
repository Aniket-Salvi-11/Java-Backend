package com.closemore.backend.tenant;

import com.closemore.backend.auth.AuthService;
import com.closemore.backend.auth.AuthenticationException;
import com.closemore.backend.auth.IssuedTokens;
import com.closemore.backend.auth.JwtService;
import com.closemore.backend.auth.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Phase 2 token machinery: signing, verification, rotation and revocation.
 *
 * <p>The property this class exists to prove is that <b>tenant and role can no longer be chosen by
 * the caller</b>. Today they arrive as X-User-Tenant and X-User-Role headers that anyone can set,
 * which makes the entire RLS design decorative. Once they are claims inside a signed token,
 * changing them requires the signing key.
 *
 * <p>Uses its own organisation, "Tokenco", so counts asserted elsewhere are untouched.
 */
class JwtAuthIT extends AbstractRlsIT {

    @Autowired
    AuthService authService;

    @Autowired
    JwtService jwtService;

    @Autowired
    RefreshTokenService refreshTokenService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedTokenUsers() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                // Sessions are per-user state that these tests mutate, so reset the whole fixture.
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Tokenco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Tokenco'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES ('tok-user','Token','User','tok@tokenco.example','Sales_Rep','Active','Tokenco','tok-pw')
                        """);
            }
            connection.commit();
        }
    }

    // ---------- what is inside the token ----------

    @Test
    void theAccessTokenCarriesTheIdentityThatRlsWillTrust() {
        IssuedTokens tokens = authService.login("tok@tokenco.example", "tok-pw").tokens();

        Jwt jwt = jwtService.verify(tokens.accessToken());

        assertThat(jwtService.userIdOf(jwt)).isEqualTo("tok-user");
        assertThat(jwtService.roleOf(jwt)).isEqualTo("Sales_Rep");
        assertThat(jwtService.tenantOf(jwt))
                .as("the tenant is sealed into the signature - a client can no longer choose it")
                .isEqualTo("Tokenco");
    }

    @Test
    void theAccessTokenExpiresWithinTheConfiguredWindow() {
        IssuedTokens tokens = authService.login("tok@tokenco.example", "tok-pw").tokens();

        // 30 minutes per application.yml. Asserted as a range rather than an exact value so that
        // changing the config is a config change, not a broken test - but a mistake that produced
        // 30 milliseconds or 30 days would still be caught.
        assertThat(tokens.accessTokenExpiresAt())
                .isAfter(Instant.now().plus(25, ChronoUnit.MINUTES))
                .isBefore(Instant.now().plus(35, ChronoUnit.MINUTES));
        assertThat(tokens.refreshTokenExpiresAt())
                .isAfter(Instant.now().plus(6, ChronoUnit.DAYS))
                .isBefore(Instant.now().plus(8, ChronoUnit.DAYS));
    }

    @Test
    void aTamperedTokenIsRejected() {
        IssuedTokens tokens = authService.login("tok@tokenco.example", "tok-pw").tokens();

        // Flip the last character of the signature. The payload is untouched, so this fails only
        // because the signature no longer matches - which is the whole basis of the design.
        String value = tokens.accessToken();
        char last = value.charAt(value.length() - 1);
        String tampered = value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');

        AuthenticationException ex = catchThrowableOfType(
                () -> jwtService.verify(tampered), AuthenticationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(401);
    }

    @Test
    void anUnsignedOrGarbageTokenIsRejected() {
        assertThat(catchThrowableOfType(() -> jwtService.verify("not.a.token"),
                AuthenticationException.class).getStatus()).isEqualTo(401);
        assertThat(catchThrowableOfType(() -> jwtService.verify(""),
                AuthenticationException.class).getStatus()).isEqualTo(401);
    }

    // ---------- refresh and rotation ----------

    @Test
    void refreshingReturnsANewPairAndConsumesTheOldRefreshToken() {
        IssuedTokens first = authService.login("tok@tokenco.example", "tok-pw").tokens();

        IssuedTokens second = authService.refresh(first.refreshToken()).tokens();

        assertThat(second.refreshToken())
                .as("rotation: each refresh token is single-use")
                .isNotEqualTo(first.refreshToken());
        assertThat(jwtService.userIdOf(jwtService.verify(second.accessToken()))).isEqualTo("tok-user");

        // Replaying the consumed token must fail. This is what makes a stolen token detectable:
        // the legitimate client's next refresh fails and the user is sent back to login, rather
        // than silently sharing a session with a thief.
        AuthenticationException ex = catchThrowableOfType(
                () -> authService.refresh(first.refreshToken()), AuthenticationException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(401);
    }

    @Test
    void anUnknownRefreshTokenIsRejected() {
        AuthenticationException ex = catchThrowableOfType(
                () -> authService.refresh("completely-made-up"), AuthenticationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(401);
    }

    @Test
    void refreshRereadsTheUserSoADeactivatedAccountStopsRenewing() {
        IssuedTokens tokens = authService.login("tok@tokenco.example", "tok-pw").tokens();

        setStatus("tok-user", "Inactive");

        AuthenticationException ex = catchThrowableOfType(
                () -> authService.refresh(tokens.refreshToken()), AuthenticationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus())
                .as("without the re-read, this session would renew itself for another week")
                .isEqualTo(403);
        assertThat(ex.getMessage()).isEqualTo("Account is inactive");
    }

    @Test
    void refreshPicksUpARoleChange() {
        IssuedTokens tokens = authService.login("tok@tokenco.example", "tok-pw").tokens();
        assertThat(jwtService.roleOf(jwtService.verify(tokens.accessToken()))).isEqualTo("Sales_Rep");

        setRole("tok-user", "Admin");

        IssuedTokens renewed = authService.refresh(tokens.refreshToken()).tokens();

        assertThat(jwtService.roleOf(jwtService.verify(renewed.accessToken())))
                .as("a role change takes effect at the next refresh, not never")
                .isEqualTo("Admin");
    }

    // ---------- revocation, the reason sessions are stored at all ----------

    @Test
    void loggingOutEndsThatSession() {
        IssuedTokens tokens = authService.login("tok@tokenco.example", "tok-pw").tokens();

        authService.logout(tokens.refreshToken());

        assertThat(catchThrowableOfType(() -> authService.refresh(tokens.refreshToken()),
                AuthenticationException.class).getStatus()).isEqualTo(401);
    }

    @Test
    void loggingOutEverywhereEndsEverySession() {
        IssuedTokens deviceOne = authService.login("tok@tokenco.example", "tok-pw").tokens();
        IssuedTokens deviceTwo = authService.login("tok@tokenco.example", "tok-pw").tokens();

        int ended = authService.logoutEverywhere("tok-user");

        assertThat(ended).isEqualTo(2);
        assertThat(catchThrowableOfType(() -> authService.refresh(deviceOne.refreshToken()),
                AuthenticationException.class).getStatus()).isEqualTo(401);
        assertThat(catchThrowableOfType(() -> authService.refresh(deviceTwo.refreshToken()),
                AuthenticationException.class).getStatus()).isEqualTo(401);
    }

    @Test
    void logoutIsIdempotentAndSaysNothingAboutWhetherTheTokenWasLive() {
        // Reporting "that token was not valid" would let a caller probe other people's sessions.
        authService.logout("never-existed");
        authService.logout("never-existed");
    }

    // ---------- the stored session ----------

    @Test
    void onlyAHashOfTheRefreshTokenIsStored() {
        IssuedTokens tokens = authService.login("tok@tokenco.example", "tok-pw").tokens();

        // Read as the superuser: the refresh_tokens policy hides the table from the application
        // connection, which is exactly what theSessionTableIsNotReadableByOrdinaryApplicationQueries
        // asserts below.
        String storedHash = queryAsSuperuser("""
                SELECT "Token_Hash" FROM refresh_tokens
                WHERE "User_ID" = 'tok-user' AND "Revoked_At" IS NULL
                """);

        assertThat(storedHash)
                .as("a leak of refresh_tokens must not yield usable tokens")
                .isNotEqualTo(tokens.refreshToken())
                .isEqualTo(RefreshTokenService.hash(tokens.refreshToken()))
                .hasSize(64);
    }

    /**
     * The refresh_tokens policy admits only callers that have set app.bypass_rls, which in practice
     * means the SECURITY DEFINER functions. This asserts a normal application connection sees
     * nothing - so a bug elsewhere cannot read every session in the system.
     */
    @Test
    void theSessionTableIsNotReadableByOrdinaryApplicationQueries() {
        authService.login("tok@tokenco.example", "tok-pw");

        Integer visible = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens", Integer.class);

        assertThat(visible).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Fixture mutation goes through a separate SUPERUSER connection, never the injected
    // JdbcTemplate. The application connects as the restricted role with no tenant context here,
    // so an UPDATE would be filtered by RLS to zero rows and report success - the test would then
    // pass or fail for reasons unrelated to what it claims to check. Same trap V14's
    // auth_store_password_hash exists to avoid.
    // ---------------------------------------------------------------------------------------

    private void setStatus(String userId, String status) {
        executeAsSuperuser("UPDATE users SET \"Status\" = '" + status
                + "' WHERE \"User_ID\" = '" + userId + "'");
    }

    private void setRole(String userId, String role) {
        executeAsSuperuser("UPDATE users SET \"Role\" = '" + role
                + "' WHERE \"User_ID\" = '" + userId + "'");
    }

    private void executeAsSuperuser(String sql) {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            stmt.execute(sql);
        } catch (Exception ex) {
            throw new IllegalStateException("fixture update failed: " + sql, ex);
        }
    }

    private String queryAsSuperuser(String sql) {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            try (var rs = stmt.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("fixture query failed: " + sql, ex);
        }
    }
}
