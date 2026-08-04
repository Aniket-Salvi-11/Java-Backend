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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The users resource group over real HTTP.
 *
 * <p>Organisation "Userco", so counts asserted elsewhere are untouched. "Rivalco" exists solely to
 * prove the tenant boundary: its rows must be invisible and unreachable from a Userco token.
 *
 * <p><b>What makes this group different from the ones before it.</b> Every other resource has one
 * authorisation rule per endpoint. {@code PUT /api/v1/users/{id}} has one rule per FIELD - anyone
 * may edit their own profile, but email, role and status are Admin-only regardless of who owns the
 * row. So the update tests are organised by field rather than by role, and the most important one
 * is {@code aSalesRepCannotPromoteThemselves}: the naive reading of "update own profile" would let
 * exactly that through.
 */
class UserApiIT extends AbstractWebIT {

    private static final String PASSWORD = "userco-password";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedUsercoUsers() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        DELETE FROM events_log WHERE "Object_Type" = 'User'
                        """);
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users
                            WHERE "Organization_Name" IN ('Userco','Rivalco'))
                        """);
                stmt.execute(
                        "DELETE FROM users WHERE \"Organization_Name\" IN ('Userco','Rivalco')");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('uc-admin','Ada','Admin','admin@userco.example','Admin','Active','Userco','%s'),
                          ('uc-rep','Rex','Rep','rep@userco.example','Sales_Rep','Active','Userco','%s'),
                          ('uc-exec','Eve','Exec','exec@userco.example','Executive','Active','Userco','%s'),
                          ('uc-pending','Pat','Pending','pending@userco.example','Admin','Pending_Approval','Userco','%s'),
                          ('rc-admin','Ron','Rival','admin@rivalco.example','Admin','Active','Rivalco','%s')
                        """.formatted(PASSWORD, PASSWORD, PASSWORD, PASSWORD, PASSWORD));
            }
            connection.commit();
        }
    }

    // --- list -------------------------------------------------------------------------------

    @Test
    void theListReturnsABareArrayByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void addingPageSwitchesToTheEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/users?page=0&size=2")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                // "items", not "content" - PageResponse is this project's own record, deliberately
                // not Spring Data's PageImpl, whose JSON has changed shape between versions.
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void theListNeverLeaksPasswordMaterial() throws Exception {
        // UserResponse has no field for either column, so this can only fail if somebody adds one.
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].Password").doesNotExist())
                .andExpect(jsonPath("$[0].Password_Hash").doesNotExist());
    }

    @Test
    void theListStopsAtTheTenantBoundary() throws Exception {
        // Rivalco's admin exists and is not in the response. RLS does this, not a service filter -
        // which is why there is no ownership predicate in UserService.
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'admin@rivalco.example')]", hasSize(0)));
    }

    @Test
    void theListCanFilterByRole() throws Exception {
        mockMvc.perform(get("/api/v1/users?role=Sales_Rep")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("rep@userco.example"));
    }

    @Test
    void theListCanFilterByStatus() throws Exception {
        mockMvc.perform(get("/api/v1/users?status=Pending_Approval")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("pending@userco.example"));
    }

    @Test
    void filtersCombineOnThePaginatedPath() throws Exception {
        mockMvc.perform(get("/api/v1/users?page=0&size=25&role=Admin&status=Pending_Approval")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].email").value("pending@userco.example"));
    }

    @Test
    void anUnknownSortColumnIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users?sort=password")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theAvatarColumnIsNotSortable() throws Exception {
        // Not an oversight in the allowlist - sorting on an inline base64 image would make Postgres
        // collate megabytes per row.
        mockMvc.perform(get("/api/v1/users?sort=avatarDataUrl")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isBadRequest());
    }

    // --- pending ----------------------------------------------------------------------------

    @Test
    void anAdminSeesThePendingQueue() throws Exception {
        mockMvc.perform(get("/api/v1/users/pending")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId").value("uc-pending"));
    }

    @Test
    void aSalesRepCannotSeeThePendingQueue() throws Exception {
        mockMvc.perform(get("/api/v1/users/pending")
                        .header("Authorization", "Bearer " + tokenFor("rep@userco.example")))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePendingPathIsNotSwallowedByAnIdRoute() throws Exception {
        // There is no GET /{userId} in this controller, so /pending is unambiguous. If one is ever
        // added it must be declared after /pending, or Spring routes this to it with
        // userId = "pending" and the queue silently 404s.
        mockMvc.perform(get("/api/v1/users/pending")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // --- create -----------------------------------------------------------------------------

    @Test
    void anAdminCanCreateAUser() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"New","lastName":"Hire","email":"NEW@userco.example",
                                 "role":"Sales_Rep"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("Sales_Rep"))
                .andExpect(jsonPath("$.status").value("Active"))
                // Lower-cased on the way in, so V13's unique index on lower("Email") behaves.
                .andExpect(jsonPath("$.email").value("new@userco.example"))
                // Organisation comes from the token, never the body.
                .andExpect(jsonPath("$.organizationName").value("Userco"));
    }

    @Test
    void aCreatedUserBelongsToTheCallersOrganisationEvenIfTheBodySaysOtherwise() throws Exception {
        // organizationName is not a field on UserCreateRequest, so an attempt to set it is ignored
        // rather than honoured. Asserted because the alternative - trusting the body - would be a
        // cross-tenant write, and the users policy WITH CHECK would turn it into a 500 rather than
        // a clear refusal.
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Cross","lastName":"Tenant","email":"cross@userco.example",
                                 "role":"Sales_Rep","organizationName":"Rivalco"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizationName").value("Userco"));
    }

    @Test
    void aSalesRepCannotCreateAUser() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor("rep@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"No","lastName":"Chance","email":"no@userco.example",
                                 "role":"Sales_Rep"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownRoleIsRejectedOnCreate() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Bad","lastName":"Role","email":"badrole@userco.example",
                                 "role":"Superuser"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInvalidEmailIsRejectedOnCreate() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Bad","lastName":"Email","email":"not-an-address",
                                 "role":"Sales_Rep"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // --- update: the per-field rules ---------------------------------------------------------

    @Test
    void anyoneCanEditTheirOwnProfileFields() throws Exception {
        mockMvc.perform(put("/api/v1/users/uc-rep")
                        .header("Authorization", "Bearer " + tokenFor("rep@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"555-0100\",\"firstName\":\"Rexy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("555-0100"))
                .andExpect(jsonPath("$.firstName").value("Rexy"));
    }

    @Test
    void aSalesRepCannotEditSomebodyElsesProfile() throws Exception {
        mockMvc.perform(put("/api/v1/users/uc-exec")
                        .header("Authorization", "Bearer " + tokenFor("rep@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"555-0199\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSalesRepCannotPromoteThemselves() throws Exception {
        // The rule that a naive reading of "update own profile" gets wrong. The row is theirs and
        // the endpoint is permitted; the FIELD is not.
        mockMvc.perform(put("/api/v1/users/uc-rep")
                        .header("Authorization", "Bearer " + tokenFor("rep@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"Admin\"}"))
                .andExpect(status().isForbidden());

        assertStoredRole("uc-rep", "Sales_Rep");
    }

    @Test
    void aSalesRepCannotActivateThemselves() throws Exception {
        mockMvc.perform(put("/api/v1/users/uc-rep")
                        .header("Authorization", "Bearer " + tokenFor("rep@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Inactive\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSalesRepCannotChangeTheirOwnEmail() throws Exception {
        // Email is Admin-only because it is the login identifier - changing it is an account
        // takeover primitive, not a profile edit.
        mockMvc.perform(put("/api/v1/users/uc-rep")
                        .header("Authorization", "Bearer " + tokenFor("rep@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"newaddress@userco.example\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanChangeRoleEmailAndStatus() throws Exception {
        mockMvc.perform(put("/api/v1/users/uc-rep")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"Executive","status":"Inactive",
                                 "email":"promoted@userco.example"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("Executive"))
                .andExpect(jsonPath("$.status").value("Inactive"))
                .andExpect(jsonPath("$.email").value("promoted@userco.example"));
    }

    @Test
    void aPartialUpdateLeavesUnmentionedFieldsAlone() throws Exception {
        mockMvc.perform(put("/api/v1/users/uc-rep")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"555-0123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("555-0123"))
                .andExpect(jsonPath("$.firstName").value("Rex"))
                .andExpect(jsonPath("$.role").value("Sales_Rep"));
    }

    @Test
    void aUserInAnotherTenantIsNotFound() throws Exception {
        // 404, not 403. RLS makes the row invisible so the service cannot tell "absent" from
        // "someone else's" - and 403 would confirm that the account exists.
        mockMvc.perform(put("/api/v1/users/rc-admin")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"555-0000\"}"))
                .andExpect(status().isNotFound());
    }

    // --- approve / reject --------------------------------------------------------------------

    @Test
    void anAdminCanApproveAPendingRegistration() throws Exception {
        mockMvc.perform(post("/api/v1/users/uc-pending/approve")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"))
                .andExpect(jsonPath("$.role").value("Admin"));
    }

    @Test
    void approvingAnAlreadyActiveUserIsRejected() throws Exception {
        // A silent no-op would hide a mistake, and treating it as a reactivation route would invent
        // an endpoint v5 does not inventory.
        mockMvc.perform(post("/api/v1/users/uc-rep/approve")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSalesRepCannotApprove() throws Exception {
        mockMvc.perform(post("/api/v1/users/uc-pending/approve")
                        .header("Authorization", "Bearer " + tokenFor("rep@userco.example")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanRejectAPendingRegistration() throws Exception {
        mockMvc.perform(post("/api/v1/users/uc-pending/reject")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isNoContent());

        assertAbsent("uc-pending");
    }

    @Test
    void rejectCannotDeleteAnEstablishedUser() throws Exception {
        // THE test for this endpoint. v5 says no route removes an established user, and that reject
        // deletes. Both are true only while this refuses every status but Pending_Approval -
        // otherwise the delete-a-user route v5 says does not exist is this one.
        mockMvc.perform(post("/api/v1/users/uc-exec/reject")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isBadRequest());

        assertStoredRole("uc-exec", "Executive");
    }

    @Test
    void aSalesRepCannotReject() throws Exception {
        mockMvc.perform(post("/api/v1/users/uc-pending/reject")
                        .header("Authorization", "Bearer " + tokenFor("rep@userco.example")))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvalIsAudited() throws Exception {
        mockMvc.perform(post("/api/v1/users/uc-pending/approve")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isOk());

        assertAuditRowCount("UPDATE", 1);
    }

    @Test
    void rejectionIsAudited() throws Exception {
        mockMvc.perform(post("/api/v1/users/uc-pending/reject")
                        .header("Authorization", "Bearer " + tokenFor("admin@userco.example")))
                .andExpect(status().isNoContent());

        assertAuditRowCount("DELETE", 1);
    }

    // --- helpers -----------------------------------------------------------------------------

    private String tokenFor(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Email\":\"" + email + "\",\"Password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private static void assertStoredRole(String userId, String expectedRole) throws Exception {
        try (Connection connection = superuser(); Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT \"Role\" FROM users WHERE \"User_ID\" = '" + userId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("Role")).isEqualTo(expectedRole);
            }
        }
    }

    private static void assertAbsent(String userId) throws Exception {
        try (Connection connection = superuser(); Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM users WHERE \"User_ID\" = '" + userId + "'")) {
                assertThat(rs.next()).isFalse();
            }
        }
    }

    private static void assertAuditRowCount(String actionType, int expected) throws Exception {
        try (Connection connection = superuser(); Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT count(*) FROM events_log WHERE \"Action_Type\" = '" + actionType
                            + "' AND \"Object_Type\" = 'User'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(expected);
            }
        }
    }

    private static Connection superuser() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
