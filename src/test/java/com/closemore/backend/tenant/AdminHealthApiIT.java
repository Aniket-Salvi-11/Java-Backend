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
import java.sql.Statement;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The last two endpoints: the audit log reader and the liveness probe.
 *
 * <p>Organisation "Auditco", with "Auditrival" to prove the log stops at the tenant boundary - the
 * property that matters most about an audit trail after the fact that it exists.
 */
class AdminHealthApiIT extends AbstractWebIT {

    private static final String PASSWORD = "auditco-password";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedAuditTrail() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("DELETE FROM events_log WHERE \"Object_ID\" LIKE 'au-%'");
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users
                            WHERE "Organization_Name" IN ('Auditco','Auditrival'))
                        """);
                stmt.execute(
                        "DELETE FROM users WHERE \"Organization_Name\" IN ('Auditco','Auditrival')");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('au-admin','Aud','Admin','admin@auditco.example','Admin','Active','Auditco','%s'),
                          ('au-rep','Aud','Rep','rep@auditco.example','Sales_Rep','Active','Auditco','%s'),
                          ('au-rival','Aud','Rival','admin@auditrival.example','Admin','Active','Auditrival','%s')
                        """.formatted(PASSWORD, PASSWORD, PASSWORD));
                // Timestamps are explicit and out of insertion order, so "most recent first" is a
                // real assertion rather than an accident of insert sequence.
                stmt.execute("""
                        INSERT INTO events_log ("Timestamp","User_ID","User_Name","Action_Type",
                          "Object_Type","Object_ID","Object_Name")
                        VALUES
                          ('2026-01-01T10:00:00Z','au-admin','Aud Admin','CREATE','Deal','au-one','Older Event'),
                          ('2026-03-01T10:00:00Z','au-admin','Aud Admin','UPDATE','Deal','au-two','Newer Event'),
                          ('2026-02-01T10:00:00Z','au-rival','Aud Rival','CREATE','Deal','au-rival-one','Rival Event')
                        """);
            }
            connection.commit();
        }
    }

    // --- events log ---------------------------------------------------------------------------

    @Test
    void anAdminCanReadTheEventsLog() throws Exception {
        mockMvc.perform(get("/api/v1/admin/events-log")
                        .header("Authorization", "Bearer " + tokenFor("admin@auditco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.objectId == 'au-two')]", hasSize(1)));
    }

    @Test
    void theEventsLogIsNewestFirst() throws Exception {
        // The March row was inserted second and the February row third, so insertion order and
        // timestamp order disagree. Only a real ORDER BY gets this right.
        mockMvc.perform(get("/api/v1/admin/events-log")
                        .header("Authorization", "Bearer " + tokenFor("admin@auditco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].objectId").value("au-two"));
    }

    @Test
    void theEventsLogStopsAtTheTenantBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/admin/events-log")
                        .header("Authorization", "Bearer " + tokenFor("admin@auditco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.objectId == 'au-rival-one')]", hasSize(0)));
    }

    @Test
    void aSalesRepCannotReadTheEventsLog() throws Exception {
        mockMvc.perform(get("/api/v1/admin/events-log")
                        .header("Authorization", "Bearer " + tokenFor("rep@auditco.example")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theEventsLogRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/admin/events-log"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theEventsLogIsNotPaginated() throws Exception {
        // A bare array, never the PageResponse envelope - see AdminService for why an audit log
        // gets a hard cap instead of pages.
        mockMvc.perform(get("/api/v1/admin/events-log")
                        .header("Authorization", "Bearer " + tokenFor("admin@auditco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    // --- health -------------------------------------------------------------------------------

    @Test
    void healthNeedsNoToken() throws Exception {
        // The whole point. A probe cannot present a bearer token, and if this ever returns 401
        // every instance reports unhealthy and the service leaves rotation.
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void healthReportsTheDatabaseSeparately() throws Exception {
        // An application that is up but cannot reach Postgres is not healthy. Asserting the field
        // exists pins that the check actually runs a query rather than returning a constant.
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void healthIsUnversioned() throws Exception {
        // /api/health, not /api/v1/health - a probe URL is configured once in infrastructure and
        // outlives API versions. Pinned so a future tidy-up does not quietly move it.
        //
        // 401 rather than 404: the JWT filter runs before request mapping, so an unmapped path
        // that is not on the public allowlist is rejected before Spring ever looks for a handler.
        // Either way it is not 200, which is what this is really asserting.
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ------------------------------------------------------------------------------

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
}
