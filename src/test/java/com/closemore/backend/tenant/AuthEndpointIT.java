package com.closemore.backend.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three session endpoints over real HTTP.
 *
 * <p>{@link JwtAuthIT} already proves the token machinery at the service level. What this adds is
 * everything between a socket and that machinery: request mapping, JSON binding of the capitalised
 * {@code Email}/{@code Password} fields the existing frontend sends, the response shape, and the
 * exception handler turning AuthenticationException into the right status and error envelope.
 * None of that is exercised by calling a service directly.
 *
 * <p>Runs in its own Spring context - see {@link AbstractWebIT} for why - and uses its own
 * organisation, "Httpco", so counts asserted elsewhere are untouched.
 */
class AuthEndpointIT extends AbstractWebIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedHttpUsers() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Httpco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Httpco'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('http-user','Http','User','http@httpco.example','Sales_Rep','Active','Httpco','http-pw'),
                          ('http-pending','Http','Pending','pending@httpco.example','Admin','Pending_Approval','Httpco','pending-pw')
                        """);
            }
            connection.commit();
        }
    }

    @Test
    void loginReturnsTokensAndTheUserBody() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"Email":"http@httpco.example","Password":"http-pw"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.accessTokenExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.refreshTokenExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.userId").value("http-user"))
                .andExpect(jsonPath("$.user.organizationName").value("Httpco"))
                .andExpect(jsonPath("$.user.role").value("Sales_Rep"));
    }

    @Test
    void theLoginResponseNeverContainsPasswordMaterial() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"Email":"http@httpco.example","Password":"http-pw"}"""))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        assertThat(body)
                .as("neither the plaintext password nor its bcrypt digest may leave the server")
                .doesNotContain("http-pw")
                .doesNotContain("$2a$")
                .doesNotContain("Password");
    }

    @Test
    void aWrongPasswordIsRejectedWithTheEstablishedErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"Email":"http@httpco.example","Password":"nope"}"""))
                .andExpect(status().isUnauthorized())
                // {"error": "..."} matches the legacy backend's jsonError, so existing frontend
                // error handling keeps working unchanged.
                .andExpect(jsonPath("$.error").value("Incorrect password"));
    }

    @Test
    void missingFieldsAreRejectedWithTheEstablishedMessage() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"Email":"http@httpco.example"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Email and Password are required"));
    }

    @Test
    void aPendingAccountIsRefusedWithTheEstablishedMessage() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"Email":"pending@httpco.example","Password":"pending-pw"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error")
                        .value("Your account is pending System Administrator approval."));
    }

    @Test
    void aMalformedBodyIs400NotAServerError() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request body"));
    }

    @Test
    void refreshExchangesTheTokenAndInvalidatesTheOldOne() throws Exception {
        String firstRefresh = jsonOf(login()).get("refreshToken").asText();

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", firstRefresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value("http-user"))
                .andReturn();

        assertThat(jsonOf(refreshed).get("refreshToken").asText())
                .as("rotation: the client must store the new value")
                .isNotEqualTo(firstRefresh);

        // Replaying the consumed token fails - this is what makes a stolen token detectable.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", firstRefresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSessionAndReturnsNoContent() throws Exception {
        String refreshToken = jsonOf(login()).get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loggingOutAnUnknownTokenStillReturnsNoContent() throws Exception {
        // Reporting "that token was not valid" would let a caller probe for live sessions.
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"never-existed"}"""))
                .andExpect(status().isNoContent());
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"Email":"http@httpco.example","Password":"http-pw"}"""))
                .andExpect(status().isOk())
                .andReturn();
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
