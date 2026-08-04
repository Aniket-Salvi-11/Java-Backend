package com.closemore.backend.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration policy and signup over real HTTP.
 *
 * <p>Uses two organisations of its own - "Newco", which starts empty to exercise bootstrap, and
 * "Oldco", which starts with an active Admin - so nothing here disturbs counts asserted elsewhere.
 *
 * <p>The three signup branches are the point. They are not in Migration Plan v5; they are derived
 * from V6's comment on users."Status", and the header of V17 records that derivation. If the JS
 * behaviour turns out to differ, these tests are where it will be visible, which is why each one
 * asserts the stored Role AND Status rather than just the HTTP status code.
 */
class SignupApiIT extends AbstractWebIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedRegistrationOrganisations() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users
                            WHERE "Organization_Name" IN ('Newco','Oldco'))
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" IN ('Newco','Oldco')");
                // Newco is deliberately left empty - that is the bootstrap fixture.
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('old-admin','Old','Admin','admin@oldco.example','Admin','Active','Oldco','pw')
                        """);
            }
            connection.commit();
        }
    }

    @Test
    void policyReportsBootstrapForAnEmptyOrganisation() throws Exception {
        mockMvc.perform(get("/api/auth/registration-policy")
                        .param("organizationName", "Newco"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootstrap").value(true))
                .andExpect(jsonPath("$.approvalPending").value(false))
                .andExpect(jsonPath("$.requiresApprover").value(false));
    }

    @Test
    void policyReportsApprovalPendingForAnEstablishedOrganisation() throws Exception {
        mockMvc.perform(get("/api/auth/registration-policy")
                        .param("organizationName", "Oldco"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootstrap").value(false))
                .andExpect(jsonPath("$.approvalPending").value(true));
    }

    @Test
    void policyIsReachableWithoutAToken() throws Exception {
        // Pins the filter allowlist. A signup form cannot present a token, so a 401 here would make
        // the whole registration flow unreachable - and it would be a config-only failure that no
        // service-level test would catch.
        mockMvc.perform(get("/api/auth/registration-policy")
                        .param("organizationName", "Newco"))
                .andExpect(status().isOk());
    }

    @Test
    void theFirstAccountInAnOrganisationBecomesItsActiveAdmin() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("founder@newco.example", "Newco", "Sales_Rep")))
                .andExpect(status().isCreated())
                // Asked for Sales_Rep, got Admin. Bootstrap overrides the request, because an
                // organisation with no Admin can never approve one.
                .andExpect(jsonPath("$.Role").value("Admin"))
                .andExpect(jsonPath("$.Status").value("Active"))
                .andExpect(jsonPath("$.Organization_Name").value("Newco"));

        assertStored("founder@newco.example", "Admin", "Active");
    }

    @Test
    void aSalesRepJoiningAnEstablishedOrganisationIsActiveImmediately() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("rep@oldco.example", "Oldco", "Sales_Rep")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.Role").value("Sales_Rep"))
                .andExpect(jsonPath("$.Status").value("Active"));

        assertStored("rep@oldco.example", "Sales_Rep", "Active");
    }

    @Test
    void aPrivilegedRoleInAnEstablishedOrganisationWaitsForApproval() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("boss@oldco.example", "Oldco", "Admin")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.Role").value("Admin"))
                .andExpect(jsonPath("$.Status").value("Pending_Approval"));

        assertStored("boss@oldco.example", "Admin", "Pending_Approval");
    }

    @Test
    void signupNeverReturnsASession() throws Exception {
        // A Pending_Approval account must not receive tokens, and returning them only sometimes
        // would give one endpoint two response shapes. Pinned so nobody "helpfully" adds them.
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("nosession@oldco.example", "Oldco", "Sales_Rep")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void theStoredPasswordIsHashedAndThePlaintextColumnIsNeverWritten() throws Exception {
        // Finding 1 in the migration plan, resolved in favour of fixing rather than porting. A new
        // account must never put a readable password in the Password column.
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("hashed@oldco.example", "Oldco", "Sales_Rep")))
                .andExpect(status().isCreated());

        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            try (ResultSet rs = stmt.executeQuery("""
                    SELECT "Password", "Password_Hash" FROM users
                     WHERE "Email" = 'hashed@oldco.example'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("Password")).isNull();
                assertThat(rs.getString("Password_Hash"))
                        .isNotNull()
                        .startsWith("$2");
                assertThat(rs.getString("Password_Hash")).doesNotContain("signup-password");
            }
        }
    }

    @Test
    void aDuplicateEmailIsRejectedWithConflict() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("dupe@oldco.example", "Oldco", "Sales_Rep")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("dupe@oldco.example", "Oldco", "Sales_Rep")))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownRoleIsRejected() throws Exception {
        // Guards the obvious escalation attempt: naming a role the system does not have, in the
        // hope that something downstream treats an unrecognised value permissively.
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("sneaky@oldco.example", "Oldco", "Superuser")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signupRequiresAnOrganisation() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"First_Name":"No","Last_Name":"Org","Email":"noorg@example.com",
                                 "Password":"signup-password","Role":"Sales_Rep"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private static String signupBody(String email, String organisation, String role) {
        return """
                {"First_Name":"New","Last_Name":"Person","Email":"%s",
                 "Password":"signup-password","Organization_Name":"%s","Role":"%s"}
                """.formatted(email, organisation, role);
    }

    /** Reads back with RLS bypassed - a pending user has no session to read itself with. */
    private static void assertStored(String email, String role, String statusValue) throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT \"Role\", \"Status\" FROM users WHERE \"Email\" = '" + email + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("Role")).isEqualTo(role);
                assertThat(rs.getString("Status")).isEqualTo(statusValue);
            }
        }
    }
}
