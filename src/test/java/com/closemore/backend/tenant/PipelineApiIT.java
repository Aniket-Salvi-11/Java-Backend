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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pipelines over real HTTP, and the stage-reassignment cascade.
 *
 * <p>The cascade tests are the reason this class is long. {@code Current_Stage} on deals is a NAME,
 * so editing a stage list can strand deals on a stage that no longer exists - and the rules for
 * which deals move are derived from v5's one-line description, not stated by it. Each rule gets its
 * own test so a wrong derivation shows up as a named failure rather than a vague one.
 *
 * <p>Like products, pipelines are global reference data with no RLS, so assertions name specific
 * ids rather than counting rows that other test classes also write.
 */
class PipelineApiIT extends AbstractWebIT {

    private static final String PASSWORD = "pipeco-password";

    private static final String THREE_STAGES = """
            [{"name":"Discovery","order":1},{"name":"Proposal","order":2},{"name":"Closed Won","order":3}]""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedPipelinesAndDeals() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("DELETE FROM events_log WHERE \"Object_Type\" = 'Pipeline'");
                stmt.execute("DELETE FROM deals WHERE \"Deal_ID\" LIKE 'pp-%'");
                stmt.execute("DELETE FROM contacts WHERE \"Contact_ID\" LIKE 'pp-%'");
                stmt.execute("DELETE FROM pipelines WHERE \"Pipeline_ID\" LIKE 'pp-%'");
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Pipeco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Pipeco'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('pp-admin','Pp','Admin','admin@pipeco.example','Admin','Active','Pipeco','%s'),
                          ('pp-rep','Pp','Rep','rep@pipeco.example','Sales_Rep','Active','Pipeco','%s')
                        """.formatted(PASSWORD, PASSWORD));
                stmt.execute("""
                        INSERT INTO pipelines ("Pipeline_ID","Pipeline_Name","Stages_JSON")
                        VALUES ('pp-main','Pipeco Main','%s'::jsonb),
                               ('pp-spare','Pipeco Spare','%s'::jsonb)
                        """.formatted(THREE_STAGES, THREE_STAGES));
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES ('pp-c1','Pip','Client','pip@buyer.example','555-3001','Buyer Ltd',
                                'Lead','Test','2026-01-01','pp-rep')
                        """);
                stmt.execute("""
                        INSERT INTO deals ("Deal_ID","Deal_Name","Associated_Contact_ID","Pipeline_ID",
                          "Current_Stage","Deal_Value","Expected_Close_Date","Probability_Percentage",
                          "Owner_ID","Status","ARR","TCV","TLV","Commission","Partner_Commission",
                          "Distributor_Commission")
                        VALUES
                          ('pp-open','Open Deal','pp-c1','pp-main','Discovery',1000.0,'2026-06-30',50,
                           'pp-rep','Open',0,0,0,0,0,0),
                          ('pp-proposal','Proposal Deal','pp-c1','pp-main','Proposal',2000.0,'2026-07-31',70,
                           'pp-rep','Open',0,0,0,0,0,0),
                          ('pp-won','Won Deal','pp-c1','pp-main','Closed Won',3000.0,'2026-05-31',100,
                           'pp-rep','Closed Won',0,0,0,0,0,0),
                          ('pp-other','Spare Deal','pp-c1','pp-spare','Discovery',400.0,'2026-08-31',20,
                           'pp-rep','Open',0,0,0,0,0,0)
                        """);
            }
            connection.commit();
        }
    }

    // --- reads ------------------------------------------------------------------------------

    @Test
    void pipelinesAreReadableByANonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/pipelines")
                        .header("Authorization", "Bearer " + tokenFor("rep@pipeco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.pipelineId == 'pp-main')]", hasSize(1)));
    }

    @Test
    void theStageListComesBackAsRawJsonText() throws Exception {
        // PipelineEntity keeps Stages_JSON as a String on purpose. If this ever comes back as a
        // nested array rather than a string, the mapping has changed and the frontend contract with it.
        mockMvc.perform(get("/api/v1/pipelines/pp-main")
                        .header("Authorization", "Bearer " + tokenFor("rep@pipeco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagesJson").isString());
    }

    @Test
    void anUnknownPipelineIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/pipelines/pp-nope")
                        .header("Authorization", "Bearer " + tokenFor("rep@pipeco.example")))
                .andExpect(status().isNotFound());
    }

    // --- create -----------------------------------------------------------------------------

    @Test
    void anAdminCanCreateAPipeline() throws Exception {
        mockMvc.perform(post("/api/v1/pipelines")
                        .header("Authorization", "Bearer " + tokenFor("admin@pipeco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("New Pipeline", THREE_STAGES)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pipelineName").value("New Pipeline"));
    }

    @Test
    void aSalesRepCannotCreateAPipeline() throws Exception {
        mockMvc.perform(post("/api/v1/pipelines")
                        .header("Authorization", "Bearer " + tokenFor("rep@pipeco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Nope", THREE_STAGES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void malformedStageJsonIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/pipelines")
                        .header("Authorization", "Bearer " + tokenFor("admin@pipeco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Broken", "not json at all")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStageWithoutANameIsRejected() throws Exception {
        // The stage NAME is what deals store, so a nameless stage would be unreachable.
        mockMvc.perform(post("/api/v1/pipelines")
                        .header("Authorization", "Bearer " + tokenFor("admin@pipeco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Nameless", "[{\"order\":1}]")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anEmptyStageListIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/pipelines")
                        .header("Authorization", "Bearer " + tokenFor("admin@pipeco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Empty", "[]")))
                .andExpect(status().isBadRequest());
    }

    // --- update: the cascade -----------------------------------------------------------------

    @Test
    void renamingAStageMovesTheDealsOnIt() throws Exception {
        // Discovery -> Qualification at position 0. pp-open sits on Discovery.
        updateStages("""
                [{"name":"Qualification","order":1},{"name":"Proposal","order":2},{"name":"Closed Won","order":3}]""")
                .andExpect(status().isOk());

        assertStage("pp-open", "Qualification");
        assertStage("pp-proposal", "Proposal");
    }

    @Test
    void renamingIsMatchedByPositionNotByName() throws Exception {
        // Both names change at once. Only positional matching gets this right, and it is the
        // assumption most likely to differ from the JS implementation - see PipelineService.update.
        updateStages("""
                [{"name":"Stage A","order":1},{"name":"Stage B","order":2},{"name":"Closed Won","order":3}]""")
                .andExpect(status().isOk());

        assertStage("pp-open", "Stage A");
        assertStage("pp-proposal", "Stage B");
    }

    @Test
    void removingAStageSweepsItsDealsToTheFirstStage() throws Exception {
        // Proposal is dropped entirely; the list shrinks rather than renaming in place.
        updateStages("""
                [{"name":"Discovery","order":1},{"name":"Closed Won","order":2}]""")
                .andExpect(status().isOk());

        // pp-proposal was on the removed stage. First stage is the only destination guaranteed to
        // exist, and moving a deal backwards is recoverable in a way that deleting its stage is not.
        assertStage("pp-proposal", "Discovery");
        assertStage("pp-open", "Discovery");
    }

    @Test
    void aClosedDealIsNeverMovedByAPipelineEdit() throws Exception {
        // THE test for this endpoint. Removing "Closed Won" must not sweep closed business back to
        // the first stage - that would reopen it and, through DealStageRules.statusForStage, change
        // its status. A dangling stage reference on a historical deal is the lesser evil.
        updateStages("""
                [{"name":"Discovery","order":1},{"name":"Proposal","order":2}]""")
                .andExpect(status().isOk());

        assertStage("pp-won", "Closed Won");
        assertStatus("pp-won", "Closed Won");
    }

    @Test
    void theCascadeDoesNotTouchOtherPipelines() throws Exception {
        // pp-other is on pp-spare, also on a stage called Discovery. A cascade keyed only on stage
        // name would move it.
        updateStages("""
                [{"name":"Renamed","order":1},{"name":"Proposal","order":2},{"name":"Closed Won","order":3}]""")
                .andExpect(status().isOk());

        assertStage("pp-open", "Renamed");
        assertStage("pp-other", "Discovery");
    }

    @Test
    void renamingOnlyThePipelineLeavesDealsAlone() throws Exception {
        mockMvc.perform(put("/api/v1/pipelines/pp-main")
                        .header("Authorization", "Bearer " + tokenFor("admin@pipeco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pipelineName\":\"Renamed Pipeline\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineName").value("Renamed Pipeline"));

        assertStage("pp-open", "Discovery");
    }

    @Test
    void anUpdateCannotEmptyTheStageList() throws Exception {
        mockMvc.perform(put("/api/v1/pipelines/pp-main")
                        .header("Authorization", "Bearer " + tokenFor("admin@pipeco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stagesJson\":\"[]\"}"))
                .andExpect(status().isBadRequest());

        assertStage("pp-open", "Discovery");
    }

    @Test
    void aSalesRepCannotEditStages() throws Exception {
        mockMvc.perform(put("/api/v1/pipelines/pp-main")
                        .header("Authorization", "Bearer " + tokenFor("rep@pipeco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pipelineName\":\"Hijacked\"}"))
                .andExpect(status().isForbidden());
    }

    // --- delete -----------------------------------------------------------------------------

    @Test
    void aPipelineInUseCannotBeDeleted() throws Exception {
        // Enforced by the foreign key, not an application count - see PipelineService.delete for
        // why an application check would be wrong across tenants.
        mockMvc.perform(delete("/api/v1/pipelines/pp-main")
                        .header("Authorization", "Bearer " + tokenFor("admin@pipeco.example")))
                .andExpect(status().isConflict());

        assertPipelineExists("pp-main", true);
    }

    @Test
    void anUnusedPipelineCanBeDeleted() throws Exception {
        try (Connection connection = superuser(); Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            stmt.execute("DELETE FROM deals WHERE \"Pipeline_ID\" = 'pp-spare'");
        }

        mockMvc.perform(delete("/api/v1/pipelines/pp-spare")
                        .header("Authorization", "Bearer " + tokenFor("admin@pipeco.example")))
                .andExpect(status().isNoContent());

        assertPipelineExists("pp-spare", false);
    }

    @Test
    void aSalesRepCannotDeleteAPipeline() throws Exception {
        mockMvc.perform(delete("/api/v1/pipelines/pp-spare")
                        .header("Authorization", "Bearer " + tokenFor("rep@pipeco.example")))
                .andExpect(status().isForbidden());
    }

    // --- helpers -----------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions updateStages(String stagesJson)
            throws Exception {
        return mockMvc.perform(put("/api/v1/pipelines/pp-main")
                .header("Authorization", "Bearer " + tokenFor("admin@pipeco.example"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("stagesJson", stagesJson))));
    }

    /**
     * Builds the request body through the mapper rather than by concatenation. stagesJson is itself
     * JSON, so embedding it in a JSON string by hand needs every quote inside it escaped - which is
     * exactly the kind of thing that fails as a confusing 400 rather than as an obvious mistake.
     */
    private String body(String name, String stagesJson) throws Exception {
        return objectMapper.writeValueAsString(
                java.util.Map.of("pipelineName", name, "stagesJson", stagesJson));
    }

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

    private static void assertStage(String dealId, String expected) throws Exception {
        assertDealColumn(dealId, "Current_Stage", expected);
    }

    private static void assertStatus(String dealId, String expected) throws Exception {
        assertDealColumn(dealId, "Status", expected);
    }

    private static void assertDealColumn(String dealId, String column, String expected)
            throws Exception {
        try (Connection connection = superuser(); Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT \"" + column + "\" FROM deals WHERE \"Deal_ID\" = '" + dealId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(column)).isEqualTo(expected);
            }
        }
    }

    private static void assertPipelineExists(String pipelineId, boolean expected) throws Exception {
        try (Connection connection = superuser(); Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM pipelines WHERE \"Pipeline_ID\" = '" + pipelineId + "'")) {
                assertThat(rs.next()).isEqualTo(expected);
            }
        }
    }

    private static Connection superuser() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
