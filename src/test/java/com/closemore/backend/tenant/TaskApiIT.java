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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tasks resource group over real HTTP.
 *
 * <p><b>What makes this group different from the three before it.</b> The tasks policy is
 * tenant-only - no owner clause - so RLS provides almost no write protection within an organisation.
 * Every assertion below about who may edit what is testing application code, not the database. If
 * the assignee check were deleted, no policy would catch it and the tests here are the only thing
 * that would.
 *
 * <p>Uses its own organisation, "Taskco", so the row counts other IT classes assert on are untouched.
 */
class TaskApiIT extends AbstractWebIT {

    private static final String PASSWORD = "taskco-pw";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * Deletion order follows the foreign keys inward: reactions reference comments, comments and
     * notifications reference tasks, tasks reference users.
     */
    @BeforeEach
    void seedTaskco() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");

                stmt.execute("""
                        DELETE FROM comment_reactions WHERE "Comment_ID" IN
                          (SELECT "Comment_ID" FROM task_comments WHERE "Task_ID" LIKE 'tk-%')
                        """);
                stmt.execute("DELETE FROM task_comments WHERE \"Task_ID\" LIKE 'tk-%'");
                stmt.execute("DELETE FROM task_notifications WHERE \"Task_ID\" LIKE 'tk-%'");
                stmt.execute("DELETE FROM task_attachments WHERE \"Task_ID\" LIKE 'tk-%'");
                stmt.execute("""
                        DELETE FROM tasks WHERE "Assigned_To" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Taskco')
                        """);
                stmt.execute("""
                        DELETE FROM events_log WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Taskco')
                        """);
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Taskco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Taskco'");

                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('tk-admin','Tk','Admin','admin@taskco.example','Admin','Active','Taskco','taskco-pw'),
                          ('tk-exec','Tk','Exec','exec@taskco.example','Executive','Active','Taskco','taskco-pw'),
                          ('tk-boss','Tk','Boss','boss@taskco.example','Sales_Rep','Active','Taskco','taskco-pw'),
                          ('tk-doer','Tk','Doer','doer@taskco.example','Sales_Rep','Active','Taskco','taskco-pw'),
                          ('tk-other','Tk','Other','other@taskco.example','Sales_Rep','Active','Taskco','taskco-pw')
                        """);
                stmt.execute("""
                        INSERT INTO tasks ("Task_ID","Task_Title","Description","Assigned_To",
                          "Assigned_By","Due_Date","Status","Is_Read")
                        VALUES
                          ('tk-t1','Call the client','Ring them back','tk-doer','tk-boss',
                           '2026-05-01','Pending',FALSE),
                          ('tk-t2','Send the quote','Email the PDF','tk-doer','tk-boss',
                           '2026-06-01','Pending',FALSE),
                          ('tk-t3','Book the venue','For the QBR','tk-boss','tk-admin',
                           '2026-04-01','Pending',TRUE)
                        """);
                stmt.execute("""
                        INSERT INTO task_comments ("Comment_ID","Task_ID","User_ID","User_Name","Content")
                        VALUES ('tk-cm1','tk-t1','tk-boss','Tk Boss','Any luck?')
                        """);
                stmt.execute("""
                        INSERT INTO task_notifications ("Notification_ID","User_ID","Task_ID",
                          "Message","Is_Read")
                        VALUES
                          ('tk-n1','tk-doer','tk-t1','Tk Boss assigned you: Call the client',FALSE),
                          ('tk-n2','tk-boss','tk-t3','Tk Admin assigned you: Book the venue',FALSE)
                        """);
            }
            connection.commit();
        }
    }

    // --- listing ------------------------------------------------------------------------------

    @Test
    void aListRequestWithNoPageParameterReturnsABareArray() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // Tenant-only policy: every rep sees every task in the organisation, including the
                // one assigned to somebody else. Weaker than contacts and deals; recorded, not fixed.
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void theTaskFieldsStayFlatAndNotificationsAreAnAddedKey() throws Exception {
        // Purely additive against the Next.js shape - an existing client ignores a key it does not
        // know about, whereas nesting the task would have broken every one of them.
        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").isNotEmpty())
                .andExpect(jsonPath("$[0].taskTitle").isNotEmpty())
                .andExpect(jsonPath("$[0].notifications").isArray());
    }

    @Test
    void theUnpaginatedListIsOrderedByDueDate() throws Exception {
        // Regression guard for the Pageable.unpaged(sort) trap - all rows return, only the ORDER BY
        // disappears. See docs/HANDOFF.md gotcha 13.
        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value("tk-t3"))
                .andExpect(jsonPath("$[1].taskId").value("tk-t1"))
                .andExpect(jsonPath("$[2].taskId").value("tk-t2"));
    }

    @Test
    void eachUserSeesOnlyTheirOwnNotifications() throws Exception {
        // task_notifications is the only table whose policy matches on app.current_user_id with no
        // organisation join, so two users reading the same task legitimately see different arrays.
        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(jsonPath("$[1].taskId").value("tk-t1"))
                .andExpect(jsonPath("$[1].notifications", hasSize(1)));

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("other@taskco.example")))
                .andExpect(jsonPath("$[1].taskId").value("tk-t1"))
                .andExpect(jsonPath("$[1].notifications", hasSize(0)));
    }

    @Test
    void askingForAPageSwitchesToTheEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/tasks?page=0&size=2")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                // The envelope must still carry the notifications key on each item.
                .andExpect(jsonPath("$.items[0].notifications").isArray());
    }

    @Test
    void anUnknownSortPropertyIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/tasks?sort=description")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot sort by 'description'"));
    }

    // --- create -------------------------------------------------------------------------------

    @Test
    void creatingATaskNotifiesTheAssignee() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("boss@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Chase the invoice","description":"Finance need it",
                                 "assignedTo":"tk-doer","dueDate":"2026-07-01"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedBy").value("tk-boss"))
                .andExpect(jsonPath("$.status").value("Pending"))
                // Unread by definition - it is the flag the assignee's badge counts.
                .andExpect(jsonPath("$.read").value(false));

        assertRowCount("SELECT count(*) FROM task_notifications WHERE \"User_ID\" = 'tk-doer' "
                + "AND \"Is_Read\" = FALSE", 2);
    }

    @Test
    void theAssignerIsAlwaysTheCallerNotTheBody() throws Exception {
        // assignedBy is not in the request record, so a client cannot set it. Asserted anyway,
        // because a forged value would let one user create work that appears to come from a manager.
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Self assigned","assignedTo":"tk-doer",
                                 "dueDate":"2026-07-01","assignedBy":"tk-admin"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedBy").value("tk-doer"));
    }

    @Test
    void aTaskCannotBeAssignedToSomeoneOutsideTheOrganisation() throws Exception {
        // user-a belongs to Acme, seeded by AbstractRlsIT. 404 rather than the 403 an RLS refusal
        // would produce: a distinct error would confirm the user id exists somewhere.
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("boss@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Cross tenant","assignedTo":"user-a",
                                 "dueDate":"2026-07-01"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void anExecutiveCannotCreateATask() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("exec@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Read only","assignedTo":"tk-doer",
                                 "dueDate":"2026-07-01"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTaskWithNoAssigneeIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + tokenFor("boss@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Orphan","dueDate":"2026-07-01"}"""))
                .andExpect(status().isBadRequest());
    }

    // --- update -------------------------------------------------------------------------------

    @Test
    void theAssigneeCanUpdateTheirTask() throws Exception {
        mockMvc.perform(put("/api/v1/tasks/tk-t1")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Call the client","description":"Ring them back",
                                 "dueDate":"2026-05-15","status":"In Progress"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("In Progress"))
                .andExpect(jsonPath("$.dueDate").value("2026-05-15"));
    }

    @Test
    void theAssignerCannotMarkTheirOwnRequestComplete() throws Exception {
        // The check is requireOwnerOrAdmin with the ASSIGNEE as owner. RLS would allow this - the
        // tasks policy is tenant-only - so this test is the only thing enforcing the rule.
        mockMvc.perform(put("/api/v1/tasks/tk-t1")
                        .header("Authorization", "Bearer " + tokenFor("boss@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Call the client","dueDate":"2026-05-01",
                                 "status":"Done"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnrelatedColleagueCannotUpdateTheTask() throws Exception {
        mockMvc.perform(put("/api/v1/tasks/tk-t1")
                        .header("Authorization", "Bearer " + tokenFor("other@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Hijacked","dueDate":"2026-05-01","status":"Done"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanUpdateAnyTask() throws Exception {
        mockMvc.perform(put("/api/v1/tasks/tk-t1")
                        .header("Authorization", "Bearer " + tokenFor("admin@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Call the client","dueDate":"2026-05-01",
                                 "status":"Blocked"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Blocked"));
    }

    @Test
    void completingATaskNotifiesTheAssigner() throws Exception {
        mockMvc.perform(put("/api/v1/tasks/tk-t1")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Call the client","dueDate":"2026-05-01",
                                 "status":"Done"}"""))
                .andExpect(status().isOk());

        assertRowCount("SELECT count(*) FROM task_notifications WHERE \"User_ID\" = 'tk-boss' "
                + "AND \"Task_ID\" = 'tk-t1'", 1);
    }

    @Test
    void doneDetectionIsCaseInsensitive() throws Exception {
        // Status is free text and "done" is what a client will send half the time. Exact matching
        // fails silently: the task closes and the assigner is never told.
        mockMvc.perform(put("/api/v1/tasks/tk-t1")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskTitle":"Call the client","dueDate":"2026-05-01",
                                 "status":"done"}"""))
                .andExpect(status().isOk());

        assertRowCount("SELECT count(*) FROM task_notifications WHERE \"User_ID\" = 'tk-boss' "
                + "AND \"Task_ID\" = 'tk-t1'", 1);
    }

    @Test
    void editingAnAlreadyDoneTaskDoesNotNotifyAgain() throws Exception {
        String done = """
                {"taskTitle":"Call the client","dueDate":"2026-05-01","status":"Done"}""";

        mockMvc.perform(put("/api/v1/tasks/tk-t1")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON).content(done))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/tasks/tk-t1")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON).content(done))
                .andExpect(status().isOk());

        // The notification fires on the TRANSITION into Done. Without the wasDone guard, every
        // subsequent save on a completed task would notify the assigner again.
        assertRowCount("SELECT count(*) FROM task_notifications WHERE \"User_ID\" = 'tk-boss' "
                + "AND \"Task_ID\" = 'tk-t1'", 1);
    }

    // --- mark-read -----------------------------------------------------------------------------

    @Test
    void markReadIsRoutedAsALiteralNotAsATaskId() throws Exception {
        // PUT /mark-read and PUT /{taskId} match the same shape. PathPatternParser sorts the literal
        // ahead of the template, so this reaches markRead rather than becoming a lookup for a task
        // called "mark-read". Not obvious, and a 404 here would be the symptom if it ever changed.
        mockMvc.perform(put("/api/v1/tasks/mark-read")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedRead").isNumber());
    }

    @Test
    void markReadClearsBothTheTaskFlagAndTheNotifications() throws Exception {
        // Two tables, because the badge is fed by two things. Clearing only one leaves a count that
        // never reaches zero - the bug users actually report.
        mockMvc.perform(put("/api/v1/tasks/mark-read")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(status().isOk())
                // tk-t1 and tk-t2 unread, plus one unread notification.
                .andExpect(jsonPath("$.markedRead").value(3));

        assertRowCount("SELECT count(*) FROM tasks WHERE \"Assigned_To\" = 'tk-doer' "
                + "AND \"Is_Read\" = FALSE", 0);
        assertRowCount("SELECT count(*) FROM task_notifications WHERE \"User_ID\" = 'tk-doer' "
                + "AND \"Is_Read\" = FALSE", 0);
    }

    @Test
    void markReadDoesNotClearAnybodyElsesUnreadFlags() throws Exception {
        // The tasks policy is tenant-only, so without the explicit assignee filter this would clear
        // the whole organisation's unread state.
        mockMvc.perform(put("/api/v1/tasks/mark-read")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(status().isOk());

        assertRowCount("SELECT count(*) FROM task_notifications WHERE \"User_ID\" = 'tk-boss' "
                + "AND \"Is_Read\" = FALSE", 1);
    }

    // --- comments and reactions -------------------------------------------------------------------

    @Test
    void commentsComeBackWithTheirReactionRollups() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/tk-t1/comments")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].commentId").value("tk-cm1"))
                .andExpect(jsonPath("$[0].userName").value("Tk Boss"))
                .andExpect(jsonPath("$[0].reactions").isArray())
                .andExpect(jsonPath("$[0].reactions", hasSize(0)));
    }

    @Test
    void aCommentIsPostedUnderTheCallersOwnName() throws Exception {
        // User_Name is denormalised and NOT NULL. Read from the users table rather than the request,
        // so a comment cannot be posted under somebody else's display name.
        mockMvc.perform(post("/api/v1/tasks/tk-t1/comments")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Left a voicemail"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("tk-doer"))
                .andExpect(jsonPath("$.userName").value("Tk Doer"))
                .andExpect(jsonPath("$.reactions", hasSize(0)));
    }

    @Test
    void commentingNotifiesTheAssigneeButNotYourself() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/tk-t1/comments")
                        .header("Authorization", "Bearer " + tokenFor("boss@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Any update?"}"""))
                .andExpect(status().isCreated());

        // The assignee gets one: the seed notification plus this one.
        assertRowCount("SELECT count(*) FROM task_notifications WHERE \"User_ID\" = 'tk-doer' "
                + "AND \"Task_ID\" = 'tk-t1'", 2);

        // The assignee commenting on their own task notifies nobody - a notification about your own
        // typing is noise.
        mockMvc.perform(post("/api/v1/tasks/tk-t1/comments")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Still trying"}"""))
                .andExpect(status().isCreated());

        assertRowCount("SELECT count(*) FROM task_notifications WHERE \"User_ID\" = 'tk-doer' "
                + "AND \"Task_ID\" = 'tk-t1'", 2);
    }

    @Test
    void aReactionTogglesOnAndOff() throws Exception {
        String url = "/api/v1/tasks/tk-t1/comments/tk-cm1/reactions";
        String body = """
                {"emoji":"\uD83D\uDC4D"}""";

        mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[0].reactedByCurrentUser").value(true));

        // Same emoji again removes it. No delete endpoint exists, and none is needed.
        mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void twoPeopleReactingWithTheSameEmojiRollUpToOneEntry() throws Exception {
        String url = "/api/v1/tasks/tk-t1/comments/tk-cm1/reactions";
        String body = """
                {"emoji":"\uD83D\uDC4D"}""";

        mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + tokenFor("other@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].count").value(2))
                .andExpect(jsonPath("$[0].reactedByCurrentUser").value(true));

        // reactedByCurrentUser is per-caller: the same comment gives different answers to different
        // readers, which is what lets the client render the toggle state without a second lookup.
        mockMvc.perform(get("/api/v1/tasks/tk-t1/comments")
                        .header("Authorization", "Bearer " + tokenFor("boss@taskco.example")))
                .andExpect(jsonPath("$[0].reactions[0].count").value(2))
                .andExpect(jsonPath("$[0].reactions[0].reactedByCurrentUser").value(false));
    }

    @Test
    void aSecondEmojiSitsBesideTheFirstRatherThanReplacingIt() throws Exception {
        String url = "/api/v1/tasks/tk-t1/comments/tk-cm1/reactions";

        mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"emoji":"\uD83D\uDC4D"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"emoji":"\uD83C\uDF89"}"""))
                .andExpect(status().isOk())
                // The toggle lookup matches on comment, user AND emoji. Matching on the first two
                // alone would make this replace the thumbs-up, which is not what any client does.
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void aCommentCannotBeReactedToThroughATaskItDoesNotBelongTo() throws Exception {
        // The comment policy authorises the row through its OWN task and cannot know which task the
        // caller named in the URL. Without the explicit check, any comment in the organisation could
        // be reached by pairing its id with a task the caller can see.
        mockMvc.perform(post("/api/v1/tasks/tk-t2/comments/tk-cm1/reactions")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"emoji":"\uD83D\uDC4D"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void anEmptyEmojiIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/tk-t1/comments/tk-cm1/reactions")
                        .header("Authorization", "Bearer " + tokenFor("doer@taskco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"emoji":""}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aRequestWithNoTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
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
                throw new AssertionError("expected " + expected + " rows, found " + actual
                        + " for: " + sql);
            }
        }
    }
}
