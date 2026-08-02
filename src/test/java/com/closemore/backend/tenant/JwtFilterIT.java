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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The end of the Phase 0 shortcut, asserted rather than assumed.
 *
 * <p>Until this batch, {@code UserContextFilter} read X-User-Id, X-User-Role and X-User-Tenant off
 * the request and believed them - so any caller could name its own tenant and read another
 * organisation's data. Every RLS policy in the schema was, in practice, decorative.
 *
 * <p>The tests below pin the three things that had to become true for that to be over: a request
 * with no token is refused, the old headers no longer establish anything, and the identity the
 * database sees comes from claims inside a signature rather than from the wire.
 *
 * <p>{@code /internal/tenant-context-echo} is the probe throughout, because it returns the Postgres
 * session variables as actually set - the values RLS will filter on. Asserting on those is stronger
 * than asserting on what the filter thinks it did.
 */
class JwtFilterIT extends AbstractWebIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedFilterUser() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Filterco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Filterco'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES ('filter-user','Filter','User','filter@filterco.example','Sales_Rep',
                                'Active','Filterco','filter-pw')
                        """);
            }
            connection.commit();
        }
    }

    @Test
    void aRequestWithNoTokenIsRefused() throws Exception {
        mockMvc.perform(get("/internal/tenant-context-echo"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void aRequestWithAGarbageTokenIsRefused() throws Exception {
        mockMvc.perform(get("/internal/tenant-context-echo")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAuthorizationHeaderWithoutTheBearerPrefixIsRefused() throws Exception {
        mockMvc.perform(get("/internal/tenant-context-echo")
                        .header("Authorization", accessToken()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * THE test. Before Phase 2 this request would have succeeded and the database would have been
     * told the caller belongs to Acme, purely because the caller said so.
     */
    @Test
    void theOldTrustedHeadersNoLongerEstablishAnything() throws Exception {
        mockMvc.perform(get("/internal/tenant-context-echo")
                        .header("X-User-Id", "user-a")
                        .header("X-User-Role", "Admin")
                        .header("X-User-Tenant", "Acme"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenEstablishesTheIdentityTheDatabaseWillSee() throws Exception {
        mockMvc.perform(get("/internal/tenant-context-echo")
                        .header("Authorization", "Bearer " + accessToken()))
                .andExpect(status().isOk())
                // These come back from current_setting() inside Postgres, so they are the values
                // RLS itself will filter on - not merely what the filter believed.
                .andExpect(jsonPath("$.userId").value("filter-user"))
                .andExpect(jsonPath("$.role").value("Sales_Rep"))
                .andExpect(jsonPath("$.tenant").value("Filterco"));
    }

    @Test
    void aTokenHoldersTenantCannotBeOverriddenByAHeader() throws Exception {
        // Both are sent. The token says Filterco, the headers say Acme. The token must win -
        // otherwise the old vulnerability survives alongside the new mechanism.
        mockMvc.perform(get("/internal/tenant-context-echo")
                        .header("Authorization", "Bearer " + accessToken())
                        .header("X-User-Id", "user-a")
                        .header("X-User-Role", "Admin")
                        .header("X-User-Tenant", "Acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("filter-user"))
                .andExpect(jsonPath("$.role").value("Sales_Rep"))
                .andExpect(jsonPath("$.tenant").value("Filterco"));
    }

    @Test
    void theSessionEndpointsStayReachableWithoutAToken() throws Exception {
        // Necessarily so - you cannot present a token in order to obtain your first one.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"Email":"filter@filterco.example","Password":"filter-pw"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void aRevokedSessionsAccessTokenStillWorksUntilItExpires() throws Exception {
        String token = accessToken();
        String refreshToken = loginJson().get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isNoContent());

        // Documents the accepted trade-off rather than a defect. Verifying an access token is a
        // signature check with no database lookup, which is what keeps it fast; the price is that
        // revocation takes effect only when the access token expires - 30 minutes by default. That
        // window is exactly why the access-token TTL was chosen short.
        mockMvc.perform(get("/internal/tenant-context-echo")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String accessToken() throws Exception {
        return loginJson().get("accessToken").asText();
    }

    private com.fasterxml.jackson.databind.JsonNode loginJson() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"Email":"filter@filterco.example","Password":"filter-pw"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
