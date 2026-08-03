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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contacts resource group over real HTTP, with a real token.
 *
 * <p><b>What this proves that nothing before it did.</b> Every earlier isolation test either called
 * a service directly with {@code asTenant(...)} setting the context by hand, or went through HTTP to
 * endpoints that never touch JPA (auth uses SECURITY DEFINER functions precisely because it runs
 * before a tenant context exists). This is the first test where a signed token arrives on a socket,
 * the filter derives the context from its claims, the aspect copies that context into Postgres
 * session variables, and a Hibernate query is then filtered by an RLS policy - the whole chain, in
 * the order production will run it.
 *
 * <p>Uses its own organisation, "Contactco", so the row counts other IT classes assert on are
 * untouched. Seeding happens on a separate superuser connection with {@code app.bypass_rls}, for the
 * reason given in {@link AbstractRlsIT}: the app role deliberately cannot bypass the policy under
 * test, so seeding through it would be filtered by that policy.
 */
class ContactApiIT extends AbstractWebIT {

    private static final String PASSWORD = "contactco-pw";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * Four users and seven contacts in one organisation.
     *
     * <p>Two Sales_Reps rather than one is what makes the ownership assertions meaningful: with a
     * single rep, "a rep sees only their own" and "a rep sees everything in the org" produce
     * identical numbers and the test proves nothing.
     *
     * <p>Deletion order matters - contacts reference users with ON DELETE RESTRICT, and the tenant
     * of a contact is derived through {@code Owner_ID}, not from its own {@code Organization_Name}
     * column (which holds the contact's employer). Filtering the delete on the latter would leave
     * rows behind and quietly inflate every count below. events_log is cleared too, because the
     * write tests below add audit rows that reference these users.
     */
    @BeforeEach
    void seedContactco() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        DELETE FROM events_log WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Contactco')
                        """);
                stmt.execute("""
                        DELETE FROM contacts WHERE "Owner_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Contactco')
                        """);
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Contactco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Contactco'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('cc-admin','Cc','Admin','admin@contactco.example','Admin','Active','Contactco','contactco-pw'),
                          ('cc-exec','Cc','Exec','exec@contactco.example','Executive','Active','Contactco','contactco-pw'),
                          ('cc-rep','Cc','Rep','rep@contactco.example','Sales_Rep','Active','Contactco','contactco-pw'),
                          ('cc-rep2','Cc','Rep2','rep2@contactco.example','Sales_Rep','Active','Contactco','contactco-pw')
                        """);
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES
                          ('cc-c1','Ada','Alpha','ada@buyer.example','555-1001','Alpha Ltd','Lead','Test','2026-01-01','cc-rep'),
                          ('cc-c2','Ben','Bravo','ben@buyer.example','555-1002','Bravo Ltd','Lead','Test','2026-01-01','cc-rep'),
                          ('cc-c3','Cal','Charlie','cal@buyer.example','555-1003','Charlie Ltd','Lead','Test','2026-01-01','cc-rep'),
                          ('cc-c4','Dee','Delta','dee@buyer.example','555-1004','Delta Ltd','Lead','Test','2026-01-01','cc-rep'),
                          ('cc-c5','Eli','Echo','eli@buyer.example','555-1005','Echo Ltd','Lead','Test','2026-01-01','cc-rep'),
                          ('cc-c6','Fay','Foxtrot','fay@buyer.example','555-1006','Foxtrot Ltd','Lead','Test','2026-01-01','cc-rep2'),
                          ('cc-c7','Gus','Golf','gus@buyer.example','555-1007','Golf Ltd','Lead','Test','2026-01-01','cc-rep2')
                        """);
            }
            connection.commit();
        }
    }

    // --- the response shape decision --------------------------------------------------------

    @Test
    void aListRequestWithNoPageParameterReturnsABareArray() throws Exception {
        // The Option A contract. This is the single most important assertion in the class: it is
        // what lets the existing web and mobile clients keep working unchanged on the day this
        // ships. If it ever fails, a frontend doing data.map(...) breaks with no server-side error.
        mockMvc.perform(get("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[0].lastName").value("Alpha"));
    }

    @Test
    void theUnpaginatedListIsFullyOrderedByTheDefaultSort() throws Exception {
        // Regression. The first version of this service passed Pageable.unpaged(sort) to
        // findAll(Pageable), which SimpleJpaRepository short-circuits to new PageImpl<>(findAll()) -
        // discarding the Sort. Every row still came back, so the count and the shape were both
        // right and nothing threw; only the ORDER BY disappeared. Asserting the whole sequence
        // rather than just the first element is what makes that unmissable.
        mockMvc.perform(get("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastName").value("Alpha"))
                .andExpect(jsonPath("$[1].lastName").value("Bravo"))
                .andExpect(jsonPath("$[2].lastName").value("Charlie"))
                .andExpect(jsonPath("$[3].lastName").value("Delta"))
                .andExpect(jsonPath("$[4].lastName").value("Echo"))
                .andExpect(jsonPath("$[5].lastName").value("Foxtrot"))
                .andExpect(jsonPath("$[6].lastName").value("Golf"));
    }

    @Test
    void anExplicitSortIsHonouredOnTheUnpaginatedPath() throws Exception {
        // The other half of the same regression: a caller-supplied ?sort= has to survive too, and
        // descending order proves the sort is genuinely applied rather than coinciding with the
        // order Postgres happens to return.
        mockMvc.perform(get("/api/v1/contacts?sort=lastName,desc")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastName").value("Golf"))
                .andExpect(jsonPath("$[6].lastName").value("Alpha"));
    }

    @Test
    void theOwnerScopedListIsOrderedToo() throws Exception {
        // A rep goes through findByOwnerId rather than findAll, so it is a second code path with
        // its own opportunity to lose the sort.
        mockMvc.perform(get("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastName").value("Alpha"))
                .andExpect(jsonPath("$[4].lastName").value("Echo"));
    }

    @Test
    void askingForAPageSwitchesToTheEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/contacts?page=0&size=3")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(3))
                // totalElements counts rows this caller may see, not rows that exist - the COUNT
                // query runs under the same policy as the SELECT.
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void sizeAloneAlsoOptsIntoTheEnvelope() throws Exception {
        // A client asking for ?size=2 plainly wants pages even without naming one. Treating size as
        // an opt-in avoids the surprise of a size parameter being silently ignored.
        mockMvc.perform(get("/api/v1/contacts?size=2")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void theLastPageReportsThatNothingFollows() throws Exception {
        mockMvc.perform(get("/api/v1/contacts?page=2&size=3")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].lastName").value("Golf"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void anOversizedPageRequestIsClampedRatherThanHonoured() throws Exception {
        // spring.data.web.pageable.max-page-size, and it applies only to the opt-in path.
        mockMvc.perform(get("/api/v1/contacts?size=100000")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    // --- who sees what ----------------------------------------------------------------------

    @Test
    void anExecutiveSeesEveryContactInTheOrganisation() throws Exception {
        // canViewAll() covers Executive as well as Admin. Worth its own case: Executive is
        // read-only elsewhere, which makes it easy to accidentally treat as restricted here too.
        mockMvc.perform(get("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("exec@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)));
    }

    @Test
    void aSalesRepSeesOnlyTheContactsItOwns() throws Exception {
        mockMvc.perform(get("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    void theOtherSalesRepSeesOnlyItsOwnTwo() throws Exception {
        // The complement of the case above. Together they rule out "the rep sees the whole org" and
        // "the rep sees nothing", both of which the previous test alone is consistent with.
        mockMvc.perform(get("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("rep2@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].contactId").value("cc-c6"));
    }

    // --- single record ----------------------------------------------------------------------

    @Test
    void aContactIsReturnedByIdWithItsFields() throws Exception {
        mockMvc.perform(get("/api/v1/contacts/cc-c1")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactId").value("cc-c1"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.ownerId").value("cc-rep"))
                // @Generated(INSERT) on Created_At - the value is read back rather than left null.
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void anotherTenantsContactIsNotFoundRatherThanForbidden() throws Exception {
        // contact-a belongs to Acme, seeded by AbstractRlsIT. 404 and not 403 on purpose: telling
        // the caller a row exists but is off-limits turns the id space into an enumeration oracle.
        mockMvc.perform(get("/api/v1/contacts/contact-a")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Contact not found"));
    }

    @Test
    void anotherRepsContactInTheSameOrganisationIsAlsoNotFound() throws Exception {
        // Same organisation, different owner - the case only a policy with an owner clause gets
        // right, and the one a tenant-only policy would let through.
        mockMvc.perform(get("/api/v1/contacts/cc-c6")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(status().isNotFound());
    }

    // --- create -----------------------------------------------------------------------------

    @Test
    void aRepCanCreateAContactAndItIsOwnedByThem() throws Exception {
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Hal","lastName":"Hotel","email":"hal@buyer.example",
                                 "phonePrimary":"555-1008","organizationName":"Hotel Ltd",
                                 "contactType":"Lead","source":"Test"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contactId").isNotEmpty())
                .andExpect(jsonPath("$.ownerId").value("cc-rep"))
                // Not supplied in the body, so the server defaults it to today rather than failing
                // on the NOT NULL column.
                .andExpect(jsonPath("$.createdDate").isNotEmpty());

        mockMvc.perform(get("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(jsonPath("$", hasSize(6)));
    }

    @Test
    void aRepCannotCreateAContactOwnedBySomeoneElse() throws Exception {
        // Quietly reassigned to the caller rather than refused: RLS would reject the insert anyway,
        // and a 403 here would confirm that the id supplied belongs to a real user.
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ivy","lastName":"India","email":"ivy@buyer.example",
                                 "phonePrimary":"555-1009","organizationName":"India Ltd",
                                 "contactType":"Lead","source":"Test","ownerId":"cc-rep2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value("cc-rep"));
    }

    @Test
    void anAdminCanCreateAContactOnBehalfOfARep() throws Exception {
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Jo","lastName":"Juliet","email":"jo@buyer.example",
                                 "phonePrimary":"555-1010","organizationName":"Juliet Ltd",
                                 "contactType":"Lead","source":"Test","ownerId":"cc-rep2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value("cc-rep2"));
    }

    @Test
    void anExecutiveCannotCreateAContact() throws Exception {
        // Executive is read-only across the application. Enforced in code, not by RLS - the
        // contacts policy has no role-based write restriction, so this is the only thing stopping it.
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("exec@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ken","lastName":"Kilo","email":"ken@buyer.example",
                                 "phonePrimary":"555-1011","organizationName":"Kilo Ltd",
                                 "contactType":"Lead","source":"Test"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCreateMissingRequiredFieldsIsRejectedWithEveryReasonAtOnce() throws Exception {
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"","lastName":"Lima","email":"not-an-email",
                                 "phonePrimary":"555-1012","organizationName":"Lima Ltd",
                                 "contactType":"Lead","source":"Test"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void aCreateWritesAnAuditEntry() throws Exception {
        // The audit write shares the caller's transaction, so this also proves the events_log
        // write-side policy added in V10 accepts an entry written through the normal request path.
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Mia","lastName":"Mike","email":"mia@buyer.example",
                                 "phonePrimary":"555-1013","organizationName":"Mike Ltd",
                                 "contactType":"Lead","source":"Test"}"""))
                .andExpect(status().isCreated());

        assertAuditRowCount("CREATE", 1);
    }

    // --- update -----------------------------------------------------------------------------

    @Test
    void anOwnerCanUpdateTheirContact() throws Exception {
        mockMvc.perform(put("/api/v1/contacts/cc-c1")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ada","lastName":"Alpha","email":"ada.new@buyer.example",
                                 "phonePrimary":"555-9999","organizationName":"Alpha Holdings",
                                 "contactType":"Customer","source":"Test"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada.new@buyer.example"))
                .andExpect(jsonPath("$.contactType").value("Customer"))
                // Not editable through this endpoint, so it must survive the replace untouched.
                .andExpect(jsonPath("$.ownerId").value("cc-rep"))
                .andExpect(jsonPath("$.createdDate").value("2026-01-01"));
    }

    @Test
    void anUpdateRecordsBothStatesInTheAuditLog() throws Exception {
        // The before-snapshot is taken before the setters run. If it were taken after, the entity
        // is managed and already mutated, so both states would record the new values and the diff
        // would be lost permanently - a mistake no other assertion would notice.
        mockMvc.perform(put("/api/v1/contacts/cc-c1")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ada","lastName":"Alpha","email":"changed@buyer.example",
                                 "phonePrimary":"555-1001","organizationName":"Alpha Ltd",
                                 "contactType":"Lead","source":"Test"}"""))
                .andExpect(status().isOk());

        assertAuditStatesDiffer("UPDATE", "cc-c1");
    }

    @Test
    void aRepCannotUpdateAnotherRepsContact() throws Exception {
        mockMvc.perform(put("/api/v1/contacts/cc-c6")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Fay","lastName":"Foxtrot","email":"hijack@buyer.example",
                                 "phonePrimary":"555-1006","organizationName":"Foxtrot Ltd",
                                 "contactType":"Lead","source":"Test"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAdminCanUpdateAnyContactInTheOrganisation() throws Exception {
        mockMvc.perform(put("/api/v1/contacts/cc-c6")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Fay","lastName":"Foxtrot","email":"fay.new@buyer.example",
                                 "phonePrimary":"555-1006","organizationName":"Foxtrot Ltd",
                                 "contactType":"Customer","source":"Test"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("fay.new@buyer.example"));
    }

    // --- delete -----------------------------------------------------------------------------

    @Test
    void anOwnerCanDeleteTheirContact() throws Exception {
        mockMvc.perform(delete("/api/v1/contacts/cc-c1")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/contacts/cc-c1")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDeleteRecordsTheRowItRemoved() throws Exception {
        // The audit entry is the only surviving copy once this commits, which is the whole reason
        // the before-state is captured rather than just the id.
        mockMvc.perform(delete("/api/v1/contacts/cc-c2")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(status().isNoContent());

        assertAuditBeforeStatePresent("DELETE", "cc-c2");
    }

    @Test
    void aRepCannotDeleteAnotherRepsContact() throws Exception {
        mockMvc.perform(delete("/api/v1/contacts/cc-c7")
                        .header("Authorization", "Bearer " + tokenFor("rep@contactco.example")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/contacts/cc-c7")
                        .header("Authorization", "Bearer " + tokenFor("rep2@contactco.example")))
                .andExpect(status().isOk());
    }

    @Test
    void anExecutiveCannotDeleteAContact() throws Exception {
        mockMvc.perform(delete("/api/v1/contacts/cc-c1")
                        .header("Authorization", "Bearer " + tokenFor("exec@contactco.example")))
                .andExpect(status().isForbidden());
    }

    // --- sorting and auth --------------------------------------------------------------------

    @Test
    void sortingByAnImageColumnIsRejectedRatherThanAttempted() throws Exception {
        // avatarDataUrl is a real, sortable-looking property deliberately off the allowlist.
        // Without it this would either collate megabytes of base64 across the table or, for a
        // genuinely unknown property, surface as a 500 for a caller-side typo.
        mockMvc.perform(get("/api/v1/contacts?sort=avatarDataUrl")
                        .header("Authorization", "Bearer " + tokenFor("admin@contactco.example")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot sort by 'avatarDataUrl'"));
    }

    @Test
    void aRequestWithNoTokenIsRefusedOnTheVersionedPrefix() throws Exception {
        // JwtFilterIT proves the filter refuses tokenless requests for /internal/*. This asserts the
        // same for /api/v1/*, a new prefix and therefore a new opportunity for something to end up
        // on PUBLIC_PATH_PREFIXES by accident. Note those prefixes match with startsWith.
        mockMvc.perform(get("/api/v1/contacts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Logs in and returns the access token. Real login rather than a hand-minted JWT, so the test
     *  breaks if the claims the filter reads ever drift from the claims login writes. */
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

    private void assertAuditRowCount(String actionType, int expected) throws Exception {
        try (Connection connection = superuserConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("SET LOCAL app.bypass_rls = 'true'");
            var rs = stmt.executeQuery(
                    "SELECT count(*) FROM events_log WHERE \"Action_Type\" = '" + actionType
                            + "' AND \"Object_Type\" = 'Contact'");
            rs.next();
            int actual = rs.getInt(1);
            if (actual != expected) {
                throw new AssertionError(
                        "expected " + expected + " " + actionType + " audit rows, found " + actual);
            }
        }
    }

    private void assertAuditStatesDiffer(String actionType, String objectId) throws Exception {
        try (Connection connection = superuserConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("SET LOCAL app.bypass_rls = 'true'");
            var rs = stmt.executeQuery(
                    "SELECT \"Before_State\", \"After_State\" FROM events_log WHERE \"Action_Type\" = '"
                            + actionType + "' AND \"Object_ID\" = '" + objectId + "'");
            if (!rs.next()) {
                throw new AssertionError("no " + actionType + " audit row for " + objectId);
            }
            String before = rs.getString(1);
            String after = rs.getString(2);
            if (before == null || after == null || before.equals(after)) {
                throw new AssertionError(
                        "audit row did not record two distinct states: before=" + before + " after=" + after);
            }
        }
    }

    private void assertAuditBeforeStatePresent(String actionType, String objectId) throws Exception {
        try (Connection connection = superuserConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("SET LOCAL app.bypass_rls = 'true'");
            var rs = stmt.executeQuery(
                    "SELECT \"Before_State\", \"After_State\" FROM events_log WHERE \"Action_Type\" = '"
                            + actionType + "' AND \"Object_ID\" = '" + objectId + "'");
            if (!rs.next()) {
                throw new AssertionError("no " + actionType + " audit row for " + objectId);
            }
            if (rs.getString(1) == null) {
                throw new AssertionError("delete audit row has no before-state - the row is unrecoverable");
            }
            if (rs.getString(2) != null) {
                throw new AssertionError("delete audit row should have no after-state");
            }
        }
    }

    private Connection superuserConnection() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        connection.setAutoCommit(false);
        return connection;
    }
}