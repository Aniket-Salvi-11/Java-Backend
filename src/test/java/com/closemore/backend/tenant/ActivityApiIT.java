package com.closemore.backend.tenant;

import com.closemore.backend.ingestion.IngestionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The activities resource group, and the first outbound integration in the project.
 *
 * <p><b>What this class proves that the previous two could not.</b> Contacts and Deals only touched
 * the database. Activities publish an ingestion event that a system outside this repository
 * consumes, and the interesting assertions here are about WHEN that happens rather than whether: a
 * note that never committed must not produce an event, because nothing can withdraw one once it is
 * on a queue.
 *
 * <p>Uses its own organisation, "Actco", so the row counts other IT classes assert on are untouched.
 */
@ContextConfiguration(classes = IngestionCaptureConfig.class)
@TestPropertySource(properties = "closemore.storage.local.root=target/test-uploads")
class ActivityApiIT extends AbstractWebIT {

    private static final String PASSWORD = "actco-pw";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedActco() throws Exception {
        IngestionCaptureConfig.PUBLISHED.clear();

        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");

                stmt.execute("DELETE FROM activity_attachments WHERE \"Log_ID\" LIKE 'ac-%'");
                stmt.execute("DELETE FROM activities WHERE \"Log_ID\" LIKE 'ac-%'");
                stmt.execute("""
                        DELETE FROM activities WHERE "Logged_By_User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Actco')
                        """);
                stmt.execute("""
                        DELETE FROM events_log WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Actco')
                        """);
                stmt.execute("""
                        DELETE FROM deals WHERE "Owner_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Actco')
                        """);
                stmt.execute("""
                        DELETE FROM contacts WHERE "Owner_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Actco')
                        """);
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Actco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Actco'");

                stmt.execute("""
                        INSERT INTO pipelines ("Pipeline_ID","Pipeline_Name","Stages_JSON")
                        VALUES ('ac-pipe','Actco Pipeline','["Prospecting","Closed Won"]'::jsonb)
                        ON CONFLICT ("Pipeline_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('ac-admin','Ac','Admin','admin@actco.example','Admin','Active','Actco','actco-pw'),
                          ('ac-exec','Ac','Exec','exec@actco.example','Executive','Active','Actco','actco-pw'),
                          ('ac-rep','Ac','Rep','rep@actco.example','Sales_Rep','Active','Actco','actco-pw'),
                          ('ac-rep2','Ac','Rep2','rep2@actco.example','Sales_Rep','Active','Actco','actco-pw')
                        """);
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES ('ac-c1','Ann','Buyer','ann@buyer.example','555-3001','Buyer Ltd',
                                'Lead','Test','2026-01-01','ac-rep')
                        """);
                stmt.execute("""
                        INSERT INTO deals ("Deal_ID","Deal_Name","Associated_Contact_ID","Pipeline_ID",
                          "Current_Stage","Deal_Value","Expected_Close_Date","Probability_Percentage",
                          "Owner_ID","Status","ARR","TCV","TLV","Commission","Partner_Commission",
                          "Distributor_Commission")
                        VALUES ('ac-d1','Actco Deal','ac-c1','ac-pipe','Prospecting',1000.0,
                                '2026-06-30',50,'ac-rep','Open',0,0,0,0,0,0)
                        """);
                stmt.execute("""
                        INSERT INTO activities ("Log_ID","Parent_Object_Type","Parent_Object_ID",
                          "Activity_Type","Summary","Detailed_Description","Log_Date","Logged_By_User_ID")
                        VALUES
                          ('ac-a1','Deal','ac-d1','Note','Kickoff call','Went well','2026-02-01','ac-rep'),
                          ('ac-a2','Deal','ac-d1','Call','Follow up','Left voicemail','2026-03-01','ac-rep'),
                          ('ac-a3','Contact','ac-c1','Note','Intro email','Sent deck','2026-04-01','ac-rep')
                        """);
            }
            connection.commit();
        }
    }

    // --- listing and filters -----------------------------------------------------------------

    @Test
    void aListRequestWithNoPageParameterReturnsABareArray() throws Exception {
        mockMvc.perform(get("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void theUnpaginatedListIsOrderedNewestFirst() throws Exception {
        // Regression guard for the Pageable.unpaged(sort) trap that cost a red run in tranche 1 -
        // the rows all come back, only the ORDER BY disappears.
        mockMvc.perform(get("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].logId").value("ac-a3"))
                .andExpect(jsonPath("$[2].logId").value("ac-a1"));
    }

    @Test
    void theListCanBeFilteredByParent() throws Exception {
        mockMvc.perform(get("/api/v1/activities?parentObjectType=Deal&parentObjectId=ac-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void aParentIdWithNoTypeIsIgnoredRatherThanAppliedAlone() throws Exception {
        // Filtering on the id alone would be a bug, not a convenience: ids come from different
        // tables with no foreign key between them, so a contact id and a deal id can collide.
        mockMvc.perform(get("/api/v1/activities?parentObjectId=ac-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void theListCanBeFilteredByType() throws Exception {
        mockMvc.perform(get("/api/v1/activities?activityType=Note")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void theListCanBeFilteredByDateRangeInclusively() throws Exception {
        // Log_Date is TEXT, so these comparisons are lexicographic and only work because the values
        // are ISO-8601. Both bounds are inclusive: a caller naming a date expects it included.
        mockMvc.perform(get("/api/v1/activities?from=2026-02-01&to=2026-03-01")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void filtersCombineRatherThanOverride() throws Exception {
        mockMvc.perform(get("/api/v1/activities?activityType=Note&parentObjectType=Contact"
                        + "&parentObjectId=ac-c1")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].logId").value("ac-a3"));
    }

    @Test
    void askingForAPageSwitchesToTheEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/activities?page=0&size=2")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void anUnknownSortPropertyIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/activities?sort=detailedDescription")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot sort by 'detailedDescription'"));
    }

    // --- visibility ---------------------------------------------------------------------------

    @Test
    void anotherRepSeesNoneOfTheseActivities() throws Exception {
        // ac-rep2 logged none of them and owns neither the parent deal nor the parent contact, so
        // all four of the policy's routes are closed.
        mockMvc.perform(get("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep2@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anAdminSeesTheWholeOrganisationsActivity() throws Exception {
        mockMvc.perform(get("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("admin@actco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    // --- create, and the ingestion hook ---------------------------------------------------------

    @Test
    void creatingANoteFiresTheIngestionEvent() throws Exception {
        mockMvc.perform(post("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentObjectType":"Deal","parentObjectId":"ac-d1",
                                 "activityType":"Note","summary":"New note",
                                 "detailedDescription":"Body of the note"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loggedByUserId").value("ac-rep"));

        List<IngestionEvent> published = IngestionCaptureConfig.PUBLISHED;
        if (published.size() != 1) {
            throw new AssertionError("expected exactly 1 ingestion event, got " + published.size());
        }
        IngestionEvent event = published.get(0);
        if (!IngestionEvent.NOTE_CREATED.equals(event.eventType())
                || !"Deal".equals(event.parentType())
                || !"ac-d1".equals(event.parentId())
                // The tenant has to travel in the event: the consumer runs outside this application
                // with no session variables and cannot derive it from anything else.
                || !"Actco".equals(event.tenant())) {
            throw new AssertionError("unexpected ingestion event: " + event);
        }
    }

    @Test
    void creatingANonNoteActivityFiresNothing() throws Exception {
        mockMvc.perform(post("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentObjectType":"Deal","parentObjectId":"ac-d1",
                                 "activityType":"Call","summary":"Rang them",
                                 "detailedDescription":"No answer"}"""))
                .andExpect(status().isCreated());

        if (!IngestionCaptureConfig.PUBLISHED.isEmpty()) {
            throw new AssertionError("a Call should not feed the AI pipeline");
        }
    }

    @Test
    void aRejectedCreateFiresNothing() throws Exception {
        // The point of the after-commit gateway. This request fails on the parent check, so nothing
        // is committed - and nothing may be published, because a queued event cannot be withdrawn
        // and the pipeline would hold a chunk for a note that never existed.
        mockMvc.perform(post("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep2@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentObjectType":"Deal","parentObjectId":"ac-d1",
                                 "activityType":"Note","summary":"Should not exist",
                                 "detailedDescription":"Parent is invisible to this rep"}"""))
                .andExpect(status().isNotFound());

        if (!IngestionCaptureConfig.PUBLISHED.isEmpty()) {
            throw new AssertionError("an event was published for a transaction that rolled back");
        }
    }

    @Test
    void anActivityCannotBeLoggedAgainstAnInvisibleParent() throws Exception {
        // Without the explicit parent check the insert would SUCCEED: the activities WITH CHECK
        // clause validates the logger's organisation, not the parent's.
        mockMvc.perform(post("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep2@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentObjectType":"Contact","parentObjectId":"ac-c1",
                                 "activityType":"Note","summary":"Not mine",
                                 "detailedDescription":"Another rep's contact"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownParentTypeIsRejected() throws Exception {
        // The column is free text with no foreign key, so 'Invoice' would insert happily - and the
        // resulting row would be invisible to everyone except its logger and Admins, with no error.
        mockMvc.perform(post("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentObjectType":"Invoice","parentObjectId":"ac-d1",
                                 "activityType":"Note","summary":"Wrong parent type",
                                 "detailedDescription":"Body"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theLoggedByUserIsAlwaysTheCallerNotTheBody() throws Exception {
        // loggedByUserId is not in the request record at all, so a client cannot set it. Asserted
        // anyway because the activities policy derives TENANCY from that column - a forged value
        // would be a tenancy bypass, not merely a lie in the audit trail.
        mockMvc.perform(post("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentObjectType":"Deal","parentObjectId":"ac-d1",
                                 "activityType":"Note","summary":"Whose note",
                                 "detailedDescription":"Body","loggedByUserId":"ac-admin"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loggedByUserId").value("ac-rep"));
    }

    @Test
    void anExecutiveCannotLogAnActivity() throws Exception {
        mockMvc.perform(post("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("exec@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentObjectType":"Deal","parentObjectId":"ac-d1",
                                 "activityType":"Note","summary":"Read only",
                                 "detailedDescription":"Body"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCreateMissingRequiredFieldsIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentObjectType":"Deal","parentObjectId":"ac-d1",
                                 "activityType":"Note","summary":""}"""))
                .andExpect(status().isBadRequest());
    }

    // --- update and delete -----------------------------------------------------------------------

    @Test
    void theAuthorCanEditTheirOwnActivity() throws Exception {
        mockMvc.perform(put("/api/v1/activities/ac-a1")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summary":"Kickoff call (revised)",
                                 "detailedDescription":"Went well, agreed next steps",
                                 "followUpDate":"2026-05-01"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Kickoff call (revised)"))
                // Not editable through this endpoint - re-parenting would hand a note to someone who
                // could not otherwise read it.
                .andExpect(jsonPath("$.parentObjectId").value("ac-d1"))
                .andExpect(jsonPath("$.activityType").value("Note"));
    }

    @Test
    void anAdminCanEditAnyoneseActivity() throws Exception {
        mockMvc.perform(put("/api/v1/activities/ac-a1")
                        .header("Authorization", "Bearer " + tokenFor("admin@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summary":"Edited by admin",
                                 "detailedDescription":"Body","followUpDate":null}"""))
                .andExpect(status().isOk());
    }

    @Test
    void editingAnActivityDoesNotRefireIngestion() throws Exception {
        // Only creation feeds the pipeline. Firing on every edit would re-embed the same note
        // repeatedly and leave the vector store holding several versions of one chunk.
        mockMvc.perform(put("/api/v1/activities/ac-a1")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summary":"Edited","detailedDescription":"Body","followUpDate":null}"""))
                .andExpect(status().isOk());

        if (!IngestionCaptureConfig.PUBLISHED.isEmpty()) {
            throw new AssertionError("an edit should not republish");
        }
    }

    @Test
    void aRepCannotEditAnotherUsersActivity() throws Exception {
        // The write check is authorship, deliberately narrower than the read: a deal owner can READ
        // every note on their deal, but editing someone else's rewrites their words under their name.
        mockMvc.perform(put("/api/v1/activities/ac-a1")
                        .header("Authorization", "Bearer " + tokenFor("rep2@actco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summary":"Hijacked","detailedDescription":"Body","followUpDate":null}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void theAuthorCanDeleteTheirOwnActivity() throws Exception {
        mockMvc.perform(delete("/api/v1/activities/ac-a2")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/activities")
                        .header("Authorization", "Bearer " + tokenFor("rep@actco.example")))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void aRequestWithNoTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/activities"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers -----------------------------------------------------------------------------------

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
