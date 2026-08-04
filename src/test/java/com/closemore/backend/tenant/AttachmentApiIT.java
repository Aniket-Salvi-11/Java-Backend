package com.closemore.backend.tenant;

import com.closemore.backend.ingestion.IngestionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Attachments on activities: upload, list, download, delete.
 *
 * <p><b>The two questions worth testing here are authorisation inheritance and path handling.</b>
 * An attachment has no tenancy of its own - it borrows the activity's, which borrows the parent
 * deal's or contact's - so the interesting failures are the ones where a caller names the wrong id
 * in the path and the policy cannot tell. The other half is the filename, which is entirely
 * client-controlled and ends up on a filesystem.
 *
 * <p>Shares {@link IngestionCaptureConfig} and the same property override with {@link ActivityApiIT}
 * so the two classes share one extra Spring context rather than starting one each.
 */
@ContextConfiguration(classes = IngestionCaptureConfig.class)
@TestPropertySource(properties = "closemore.storage.local.root=target/test-uploads")
class AttachmentApiIT extends AbstractWebIT {

    private static final String PASSWORD = "attco-pw";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedAttco() throws Exception {
        IngestionCaptureConfig.PUBLISHED.clear();

        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");

                stmt.execute("DELETE FROM activity_attachments WHERE \"Log_ID\" LIKE 'at-%'");
                stmt.execute("DELETE FROM activities WHERE \"Log_ID\" LIKE 'at-%'");
                stmt.execute("""
                        DELETE FROM events_log WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Attco')
                        """);
                stmt.execute("""
                        DELETE FROM contacts WHERE "Owner_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Attco')
                        """);
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Attco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Attco'");

                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('at-admin','At','Admin','admin@attco.example','Admin','Active','Attco','attco-pw'),
                          ('at-exec','At','Exec','exec@attco.example','Executive','Active','Attco','attco-pw'),
                          ('at-rep','At','Rep','rep@attco.example','Sales_Rep','Active','Attco','attco-pw'),
                          ('at-rep2','At','Rep2','rep2@attco.example','Sales_Rep','Active','Attco','attco-pw')
                        """);
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES
                          ('at-c1','Amy','Buyer','amy@buyer.example','555-4001','Buyer Ltd',
                           'Lead','Test','2026-01-01','at-rep'),
                          ('at-c2','Bob','Buyer','bob@buyer.example','555-4002','Buyer Two Ltd',
                           'Lead','Test','2026-01-01','at-rep2')
                        """);
                // Two activities on DIFFERENT parents, so the "wrong logId in the path" test has
                // somewhere real to point at.
                stmt.execute("""
                        INSERT INTO activities ("Log_ID","Parent_Object_Type","Parent_Object_ID",
                          "Activity_Type","Summary","Detailed_Description","Log_Date","Logged_By_User_ID")
                        VALUES
                          ('at-a1','Contact','at-c1','Note','Amy notes','Body','2026-02-01','at-rep'),
                          ('at-a2','Contact','at-c1','Note','More notes','Body','2026-02-02','at-rep'),
                          ('at-a3','Contact','at-c2','Note','Bob notes','Body','2026-02-03','at-rep2')
                        """);
            }
            connection.commit();
        }
    }

    // --- upload ------------------------------------------------------------------------------

    @Test
    void aFileCanBeUploadedAndThenListed() throws Exception {
        mockMvc.perform(multipart("/api/v1/attachments/at-a1")
                        .file(file("proposal.pdf", "application/pdf", "pdf bytes"))
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fileName").value("proposal.pdf"))
                .andExpect(jsonPath("$[0].mimeType").value("application/pdf"))
                .andExpect(jsonPath("$[0].uploadedByUserId").value("at-rep"))
                // Computed from the bytes, never taken from the client.
                .andExpect(jsonPath("$[0].fileSize").value(9));

        mockMvc.perform(get("/api/v1/attachments/at-a1")
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void severalFilesCanBeUploadedInOneRequest() throws Exception {
        mockMvc.perform(multipart("/api/v1/attachments/at-a1")
                        .file(file("one.pdf", "application/pdf", "first"))
                        .file(file("two.pdf", "application/pdf", "second"))
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void aDocumentUploadFiresDocumentIngestion() throws Exception {
        mockMvc.perform(multipart("/api/v1/attachments/at-a1")
                        .file(file("contract.pdf", "application/pdf", "pdf bytes"))
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isCreated());

        assertSingleEvent(IngestionEvent.DOCUMENT_UPLOADED, "Contact", "at-c1");
    }

    @Test
    void anAudioUploadFiresRecordingIngestion() throws Exception {
        // The distinction decides whether the pipeline transcribes or extracts text. Getting it
        // wrong does not fail - the file is processed by the wrong service and produces nothing.
        mockMvc.perform(multipart("/api/v1/attachments/at-a1")
                        .file(file("call.mp3", "audio/mpeg", "audio bytes"))
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isCreated());

        assertSingleEvent(IngestionEvent.RECORDING_UPLOADED, "Contact", "at-c1");
    }

    @Test
    void anUnknownMimeTypeIsTreatedAsADocument() throws Exception {
        // Extraction on a file it cannot read produces nothing; transcription on a non-audio file
        // wastes a paid API call per upload. Documents are the cheaper wrong guess.
        mockMvc.perform(multipart("/api/v1/attachments/at-a1")
                        .file(file("mystery.bin", "application/octet-stream", "bytes"))
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isCreated());

        assertSingleEvent(IngestionEvent.DOCUMENT_UPLOADED, "Contact", "at-c1");
    }

    @Test
    void aRejectedUploadFiresNothing() throws Exception {
        // at-a3 hangs off another rep's contact, so the activity is invisible and the request never
        // commits. Nothing may be published: a queued event cannot be withdrawn.
        mockMvc.perform(multipart("/api/v1/attachments/at-a3")
                        .file(file("sneaky.pdf", "application/pdf", "bytes"))
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isNotFound());

        if (!IngestionCaptureConfig.PUBLISHED.isEmpty()) {
            throw new AssertionError("an event was published for a transaction that rolled back");
        }
    }

    @Test
    void anEmptyFileIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/v1/attachments/at-a1")
                        .file(file("empty.pdf", "application/pdf", ""))
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anExecutiveCannotUpload() throws Exception {
        mockMvc.perform(multipart("/api/v1/attachments/at-a1")
                        .file(file("readonly.pdf", "application/pdf", "bytes"))
                        .header("Authorization", "Bearer " + tokenFor("exec@attco.example")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTraversalFilenameIsStoredSafelyAndKeptVerbatimInTheDatabase() throws Exception {
        // The filename is entirely client-controlled and ends up on a filesystem. The provider
        // generates the real name; the original stays in the column, where it is data rather than an
        // instruction. A 201 here plus a successful download is the whole assertion: if the name had
        // been used as a path, the write would have escaped the storage root or failed.
        mockMvc.perform(multipart("/api/v1/attachments/at-a1")
                        .file(file("../../../etc/passwd", "text/plain", "not really passwd"))
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].fileName").value("../../../etc/passwd"))
                .andExpect(jsonPath("$[0].storagePath").value(
                        org.hamcrest.Matchers.startsWith("at-a1/")));
    }

    // --- download ------------------------------------------------------------------------------

    @Test
    void anUploadedFileCanBeDownloadedBackByteForByte() throws Exception {
        String attachmentId = uploadAndGetId("notes.txt", "text/plain", "the original content");

        mockMvc.perform(get("/api/v1/attachments/at-a1/open/" + attachmentId)
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isOk())
                .andExpect(content().string("the original content"))
                // attachment, never inline: serving user-uploaded HTML or SVG inline would let it
                // execute in the application's own origin.
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''notes.txt"));
    }

    @Test
    void aFilenameWithSpacesAndAccentsSurvivesTheHeader() throws Exception {
        // The filename is user input and may contain quotes, semicolons or non-ASCII, any of which
        // would otherwise break out of the header or corrupt it. RFC 5987 encoding is why this works.
        String attachmentId = uploadAndGetId("café notes.txt", "text/plain", "body");

        mockMvc.perform(get("/api/v1/attachments/at-a1/open/" + attachmentId)
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''caf%C3%A9%20notes.txt"));
    }

    @Test
    void anAttachmentCannotBeReachedThroughAnActivityItDoesNotBelongTo() throws Exception {
        // The attachment policy authorises the row through its OWN activity and cannot know which
        // activity the caller named in the URL. Without the explicit check, a caller with access to
        // at-a2 could stream at-a1's attachment simply by putting the wrong id in the path.
        String attachmentId = uploadAndGetId("private.txt", "text/plain", "body");

        mockMvc.perform(get("/api/v1/attachments/at-a2/open/" + attachmentId)
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherRepCannotDownloadTheFile() throws Exception {
        String attachmentId = uploadAndGetId("confidential.txt", "text/plain", "body");

        mockMvc.perform(get("/api/v1/attachments/at-a1/open/" + attachmentId)
                        .header("Authorization", "Bearer " + tokenFor("rep2@attco.example")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherRepCannotEvenListTheAttachments() throws Exception {
        mockMvc.perform(get("/api/v1/attachments/at-a1")
                        .header("Authorization", "Bearer " + tokenFor("rep2@attco.example")))
                .andExpect(status().isNotFound());
    }

    // --- delete ---------------------------------------------------------------------------------

    @Test
    void theUploaderCanDeleteTheirOwnAttachment() throws Exception {
        String attachmentId = uploadAndGetId("removable.txt", "text/plain", "body");

        mockMvc.perform(delete("/api/v1/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/attachments/at-a1")
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anAdminCanDeleteSomebodyElsesAttachment() throws Exception {
        String attachmentId = uploadAndGetId("adminremovable.txt", "text/plain", "body");

        mockMvc.perform(delete("/api/v1/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + tokenFor("admin@attco.example")))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletingTheActivityRemovesItsAttachmentsToo() throws Exception {
        uploadAndGetId("cascade.txt", "text/plain", "body");

        mockMvc.perform(delete("/api/v1/activities/at-a1")
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isNoContent());

        // activity_attachments has a real foreign key to activities, so a row left behind would
        // have made the activity delete fail outright rather than orphan anything.
        assertRowCount("SELECT count(*) FROM activity_attachments WHERE \"Log_ID\" = 'at-a1'", 0);
    }

    @Test
    void aRequestWithNoTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/attachments/at-a1"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ----------------------------------------------------------------------------------

    private MockMultipartFile file(String name, String contentType, String body) {
        return new MockMultipartFile("files", name, contentType,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private String uploadAndGetId(String name, String contentType, String body) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/attachments/at-a1")
                        .file(file(name, contentType, body))
                        .header("Authorization", "Bearer " + tokenFor("rep@attco.example")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get(0).get("attachmentId").asText();
    }

    private void assertSingleEvent(String expectedType, String parentType, String parentId) {
        if (IngestionCaptureConfig.PUBLISHED.size() != 1) {
            throw new AssertionError("expected exactly 1 ingestion event, got "
                    + IngestionCaptureConfig.PUBLISHED.size());
        }
        IngestionEvent event = IngestionCaptureConfig.PUBLISHED.get(0);
        if (!expectedType.equals(event.eventType())
                || !parentType.equals(event.parentType())
                || !parentId.equals(event.parentId())
                || !"Attco".equals(event.tenant())) {
            throw new AssertionError("unexpected ingestion event: " + event);
        }
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

    private void assertRowCount(String sql, int expected) throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            connection.setAutoCommit(false);
            stmt.execute("SET LOCAL app.bypass_rls = 'true'");
            var rs = stmt.executeQuery(sql);
            rs.next();
            int actual = rs.getInt(1);
            if (actual != expected) {
                throw new AssertionError("expected " + expected + " rows, found " + actual);
            }
        }
    }
}
