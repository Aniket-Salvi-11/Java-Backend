package com.closemore.backend.tenant;

import com.closemore.backend.auth.AuthenticatedUserRow;
import com.closemore.backend.auth.AuthenticationException;
import com.closemore.backend.auth.LoginService;
import com.closemore.backend.auth.PasswordService;
import com.closemore.backend.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Phase 2 login: credential verification, status gating, and the lazy plaintext-to-bcrypt
 * migration.
 *
 * <p>Every test here runs with NO tenant context, because that is the real condition at login -
 * the tenant is not known until the user is identified. That makes this the only part of the
 * system that legitimately reads a users row without one, and the reason it goes through the
 * SECURITY DEFINER functions from V11 and V14 rather than a repository.
 *
 * <p>Uses its own organisation, "Loginco", so the Acme and Globex row counts asserted across the
 * rest of the suite are untouched.
 */
class LoginIT extends AbstractRlsIT {

    @Autowired
    LoginService loginService;

    @Autowired
    PasswordService passwordService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedLoginUsers() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                // Reset on every run so the migration test always starts un-migrated - it mutates
                // the row it tests, and all IT classes share one database.
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Loginco'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('login-legacy','Legacy','User','legacy@loginco.example','Sales_Rep','Active','Loginco','correct horse'),
                          ('login-pending','Pending','User','pending@loginco.example','Admin','Pending_Approval','Loginco','pending-pw'),
                          ('login-inactive','Inactive','User','inactive@loginco.example','Sales_Rep','Inactive','Loginco','inactive-pw')
                        """);
            }
            connection.commit();
        }
    }

    // ---------- the lazy migration, the point of this batch ----------

    @Test
    void aLegacyPlaintextPasswordStillLogsInAndIsMigratedToBcrypt() {
        assertThat(storedHashFor("login-legacy"))
                .as("precondition: this user starts un-migrated")
                .isNull();

        AuthenticatedUserRow user = loginService.authenticate("legacy@loginco.example", "correct horse");

        assertThat(user.userId()).isEqualTo("login-legacy");

        String hash = storedHashFor("login-legacy");
        assertThat(hash)
                .as("auth_store_password_hash must have written the digest - a plain UPDATE here "
                        + "would be silently filtered to zero rows by RLS")
                .isNotNull()
                .startsWith("$2");
        assertThat(passwordService.matches("correct horse", hash)).isTrue();

        assertThat(plaintextFor("login-legacy"))
                .as("the legacy column must be left intact - the JS backend still reads it")
                .isEqualTo("correct horse");
    }

    @Test
    void theSecondLoginVerifiesAgainstTheHashAndDoesNotRewriteIt() {
        loginService.authenticate("legacy@loginco.example", "correct horse");
        String firstHash = storedHashFor("login-legacy");

        loginService.authenticate("legacy@loginco.example", "correct horse");

        assertThat(storedHashFor("login-legacy"))
                .as("auth_store_password_hash is single-shot via its Password_Hash IS NULL guard")
                .isEqualTo(firstHash);
    }

    @Test
    void aWrongPasswordDoesNotMigrateAnything() {
        AuthenticationException ex = catchThrowableOfType(
                () -> loginService.authenticate("legacy@loginco.example", "wrong"),
                AuthenticationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(401);
        assertThat(storedHashFor("login-legacy")).isNull();
    }

    // ---------- credential handling ----------

    @Test
    void emailMatchingIsCaseAndWhitespaceInsensitive() {
        AuthenticatedUserRow user = loginService.authenticate("  LEGACY@LoginCo.Example  ", "correct horse");

        assertThat(user.userId()).isEqualTo("login-legacy");
    }

    @Test
    void anUnknownEmailIsRejected() {
        AuthenticationException ex = catchThrowableOfType(
                () -> loginService.authenticate("nobody@loginco.example", "whatever"),
                AuthenticationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(401);
    }

    @Test
    void missingCredentialsAreRejectedBeforeAnyLookup() {
        assertThat(catchThrowableOfType(() -> loginService.authenticate("", "pw"),
                AuthenticationException.class).getStatus()).isEqualTo(400);
        assertThat(catchThrowableOfType(() -> loginService.authenticate("a@b.example", "  "),
                AuthenticationException.class).getStatus()).isEqualTo(400);
    }

    // ---------- status gating (V6: Active | Inactive | Pending_Approval) ----------

    @Test
    void aPendingApprovalAccountIsRefusedWithTheEstablishedMessage() {
        AuthenticationException ex = catchThrowableOfType(
                () -> loginService.authenticate("pending@loginco.example", "pending-pw"),
                AuthenticationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(403);
        assertThat(ex.getMessage())
                .as("reproduced verbatim from the legacy backend - the frontend may match on it")
                .isEqualTo("Your account is pending System Administrator approval.");
    }

    @Test
    void anInactiveAccountIsRefused() {
        AuthenticationException ex = catchThrowableOfType(
                () -> loginService.authenticate("inactive@loginco.example", "inactive-pw"),
                AuthenticationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(403);
        assertThat(ex.getMessage()).isEqualTo("Account is inactive");
    }

    @Test
    void statusIsCheckedAfterThePasswordNotBefore() {
        // Otherwise anyone could discover which accounts are pending approval without knowing a
        // password - a 403 for a wrong password would confirm the account exists and its state.
        AuthenticationException ex = catchThrowableOfType(
                () -> loginService.authenticate("pending@loginco.example", "wrong-password"),
                AuthenticationException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus())
                .as("a wrong password must look the same whatever the account status")
                .isEqualTo(401);
    }

    // ---------- what leaves the application ----------

    @Test
    void theResponseShapeCarriesNoPasswordMaterial() {
        AuthenticatedUserRow user = loginService.authenticate("legacy@loginco.example", "correct horse");

        UserResponse response = loginService.toResponse(user);

        assertThat(response.userId()).isEqualTo("login-legacy");
        assertThat(response.organizationName()).isEqualTo("Loginco");
        assertThat(response.role()).isEqualTo("Sales_Rep");
        // UserResponse has no password field at all, so this is a shape assertion rather than a
        // null check: toString() is the cheapest way to prove nothing leaked into it.
        assertThat(response.toString()).doesNotContain("correct horse").doesNotContain("$2");
    }

    // Reads go through the lookup function rather than a plain SELECT for the same reason
    // LoginService does: these assertions run with no tenant context, so a direct query on users
    // would be filtered to zero rows by RLS and the test would fail for the wrong reason.
    private String storedHashFor(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT \"Password_Hash\" FROM auth_lookup_user_by_email(?)",
                String.class, emailFor(userId));
    }

    private String plaintextFor(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT \"Password\" FROM auth_lookup_user_by_email(?)",
                String.class, emailFor(userId));
    }

    private String emailFor(String userId) {
        return switch (userId) {
            case "login-legacy" -> "legacy@loginco.example";
            case "login-pending" -> "pending@loginco.example";
            case "login-inactive" -> "inactive@loginco.example";
            default -> throw new IllegalArgumentException(userId);
        };
    }
}
