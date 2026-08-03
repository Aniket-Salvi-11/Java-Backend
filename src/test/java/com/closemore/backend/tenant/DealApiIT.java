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
 * The deals resource group over real HTTP.
 *
 * <p><b>What this class proves that ContactApiIT could not.</b> Contacts have a one-hop policy:
 * you own the row or you do not. Deals have three access routes - owner, team member, and
 * Admin/Executive - and the team route was added by V9 specifically so a rep can work a deal they do
 * not own. Every ownership assertion below exists in a pair: one rep who should see the deal through
 * team membership, one who should not see it at all. A single-rep test would pass against a broken
 * implementation that simply returned everything.
 *
 * <p>Uses its own organisation, "Dealco", so the row counts other IT classes assert on are untouched.
 * Seeding happens on a separate superuser connection with {@code app.bypass_rls}, for the reason
 * given in {@link AbstractRlsIT}.
 */
class DealApiIT extends AbstractWebIT {

    private static final String PASSWORD = "dealco-pw";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * One organisation, five users, three deals.
     *
     * <p>dl-rep owns dl-d1 and dl-d2. dl-rep2 owns dl-d3 and is on NO team. dl-team is on dl-d1's
     * team but owns nothing - that user is the whole point of this fixture, and the reason the
     * visibility assertions can distinguish "owner" from "may see".
     *
     * <p>Deletion order matters: line_items, deal_contacts and deal_team_members cascade from deals,
     * but activities do not (they reference a deal through an untyped text pair with no foreign key),
     * so they are removed explicitly. Deals must go before contacts and users, both of which they
     * reference with ON DELETE RESTRICT.
     */
    @BeforeEach
    void seedDealco() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");

                stmt.execute("DELETE FROM activities WHERE \"Parent_Object_ID\" LIKE 'dl-%'");
                stmt.execute("""
                        DELETE FROM events_log WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Dealco')
                        """);
                stmt.execute("""
                        DELETE FROM deals WHERE "Owner_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Dealco')
                        """);
                stmt.execute("""
                        DELETE FROM contacts WHERE "Owner_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Dealco')
                        """);
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Dealco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Dealco'");

                // Reference data: no RLS on these two by design, so they are shared and inserted
                // idempotently rather than deleted and recreated.
                stmt.execute("""
                        INSERT INTO pipelines ("Pipeline_ID","Pipeline_Name","Stages_JSON")
                        VALUES ('dl-pipe','Dealco Pipeline',
                          '["Prospecting","Negotiation","Closed Won","Closed Lost"]'::jsonb)
                        ON CONFLICT ("Pipeline_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO products ("Product_ID","Name","SKU_Code","Type","Unit_Price",
                          "Description","Is_Active")
                        VALUES ('dl-prod','Dealco Widget','DL-SKU-1','Licence',100.0,'Test product',TRUE)
                        ON CONFLICT ("Product_ID") DO NOTHING
                        """);

                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('dl-admin','Dl','Admin','admin@dealco.example','Admin','Active','Dealco','dealco-pw'),
                          ('dl-exec','Dl','Exec','exec@dealco.example','Executive','Active','Dealco','dealco-pw'),
                          ('dl-rep','Dl','Rep','rep@dealco.example','Sales_Rep','Active','Dealco','dealco-pw'),
                          ('dl-rep2','Dl','Rep2','rep2@dealco.example','Sales_Rep','Active','Dealco','dealco-pw'),
                          ('dl-team','Dl','Team','team@dealco.example','Sales_Rep','Active','Dealco','dealco-pw')
                        """);
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES
                          ('dl-c1','Dee','Client','dee@buyer.example','555-2001','Buyer Ltd',
                           'Lead','Test','2026-01-01','dl-rep'),
                          ('dl-c2','Eve','Client','eve@buyer.example','555-2002','Buyer Two Ltd',
                           'Lead','Test','2026-01-01','dl-rep')
                        """);
                stmt.execute("""
                        INSERT INTO deals ("Deal_ID","Deal_Name","Associated_Contact_ID","Pipeline_ID",
                          "Current_Stage","Deal_Value","Expected_Close_Date","Probability_Percentage",
                          "Owner_ID","Status","ARR","TCV","TLV","Commission","Partner_Commission",
                          "Distributor_Commission")
                        VALUES
                          ('dl-d1','Alpha Deal','dl-c1','dl-pipe','Prospecting',1000.0,'2026-06-30',50,
                           'dl-rep','Open',0,0,0,0,0,0),
                          ('dl-d2','Bravo Deal','dl-c1','dl-pipe','Negotiation',2000.0,'2026-07-31',70,
                           'dl-rep','Open',0,0,0,0,0,0),
                          ('dl-d3','Charlie Deal','dl-c2','dl-pipe','Prospecting',3000.0,'2026-08-31',30,
                           'dl-rep2','Open',0,0,0,0,0,0)
                        """);
                stmt.execute("""
                        INSERT INTO deal_team_members ("Deal_ID","User_ID") VALUES ('dl-d1','dl-team')
                        """);
                stmt.execute("""
                        INSERT INTO deal_contacts ("Deal_ID","Contact_ID","Is_Primary")
                        VALUES ('dl-d1','dl-c1',TRUE)
                        """);
                stmt.execute("""
                        INSERT INTO line_items ("Line_Item_ID","Deal_ID","Product_ID","Quantity",
                          "Unit_Price_At_Sale","Discount_Amount","Total_Line_Value")
                        VALUES ('dl-li1','dl-d1','dl-prod',2,100.0,0.0,200.0)
                        """);
                stmt.execute("""
                        INSERT INTO activities ("Log_ID","Parent_Object_Type","Parent_Object_ID",
                          "Activity_Type","Summary","Detailed_Description","Log_Date","Logged_By_User_ID")
                        VALUES ('dl-a1','Deal','dl-d1','Note','First call','Went well','2026-02-01','dl-rep')
                        """);
            }
            connection.commit();
        }
    }

    // --- visibility -------------------------------------------------------------------------

    @Test
    void anAdminSeesEveryDealInTheOrganisation() throws Exception {
        mockMvc.perform(get("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenFor("admin@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void anOwnerSeesOnlyTheDealsTheyOwn() throws Exception {
        mockMvc.perform(get("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void aTeamMemberSeesTheDealTheyAreOnWithoutOwningIt() throws Exception {
        // The reason DealSpecifications.visibleTo is an OR and not an owner equality. A filter that
        // only matched ownership would be STRICTER than the V9 policy, and this deal would silently
        // vanish from the list with no error anywhere.
        mockMvc.perform(get("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenFor("team@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].dealId").value("dl-d1"))
                .andExpect(jsonPath("$[0].ownerId").value("dl-rep"));
    }

    @Test
    void anUnrelatedRepSeesNeitherTheDealNorItsExistence() throws Exception {
        mockMvc.perform(get("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenFor("rep2@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].dealId").value("dl-d3"));

        mockMvc.perform(get("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep2@dealco.example")))
                .andExpect(status().isNotFound());
    }

    // --- filters, sorting, pagination ---------------------------------------------------------

    @Test
    void theListCanBeFilteredByStage() throws Exception {
        mockMvc.perform(get("/api/v1/deals?stage=Negotiation")
                        .header("Authorization", "Bearer " + tokenFor("admin@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].dealId").value("dl-d2"));
    }

    @Test
    void theListCanBeFilteredByOwner() throws Exception {
        mockMvc.perform(get("/api/v1/deals?ownerId=dl-rep2")
                        .header("Authorization", "Bearer " + tokenFor("admin@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ownerId").value("dl-rep2"));
    }

    @Test
    void filtersCombineRatherThanOverride() throws Exception {
        // Two filters that each match something, but nothing at once. A specification chain that
        // ORed instead of ANDed, or that let the last filter win, would return rows here.
        mockMvc.perform(get("/api/v1/deals?stage=Negotiation&ownerId=dl-rep2")
                        .header("Authorization", "Bearer " + tokenFor("admin@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aFilterCannotWidenWhatARepIsAllowedToSee() throws Exception {
        // The visibility predicate and the caller-supplied filter are ANDed, so naming another
        // user's id narrows the result to nothing rather than reaching their deals.
        mockMvc.perform(get("/api/v1/deals?ownerId=dl-rep")
                        .header("Authorization", "Bearer " + tokenFor("rep2@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void askingForAPageSwitchesToTheEnvelopeAndCountsOnlyVisibleRows() throws Exception {
        mockMvc.perform(get("/api/v1/deals?page=0&size=2")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                // Two, not three: the COUNT query runs under the same policy as the SELECT.
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void anUnknownSortPropertyIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/deals?sort=winLossReason")
                        .header("Authorization", "Bearer " + tokenFor("admin@dealco.example")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot sort by 'winLossReason'"));
    }

    // --- detail and story ---------------------------------------------------------------------

    @Test
    void theDetailEndpointAssemblesEverythingHangingOffTheDeal() throws Exception {
        mockMvc.perform(get("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deal.dealId").value("dl-d1"))
                .andExpect(jsonPath("$.lineItems", hasSize(1)))
                .andExpect(jsonPath("$.contacts", hasSize(1)))
                .andExpect(jsonPath("$.team", hasSize(1)))
                .andExpect(jsonPath("$.team[0].userId").value("dl-team"))
                .andExpect(jsonPath("$.activities", hasSize(1)))
                .andExpect(jsonPath("$.attachments", hasSize(0)));
    }

    @Test
    void theStoryEndpointReturnsAuditEntriesAndActivitiesSeparately() throws Exception {
        // Produce one audit entry first, so the trail is not trivially empty.
        mockMvc.perform(put("/api/v1/deals/dl-d1/stage")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentStage":"Negotiation"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/deals/dl-d1/story")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealId").value("dl-d1"))
                .andExpect(jsonPath("$.auditTrail", hasSize(1)))
                .andExpect(jsonPath("$.activities", hasSize(1)));
    }

    @Test
    void theStoryOfAnInvisibleDealIsNotFoundRatherThanEmpty() throws Exception {
        // Without the explicit load, an unauthorised caller would receive an empty timeline, which
        // is indistinguishable from a deal nothing has happened to.
        mockMvc.perform(get("/api/v1/deals/dl-d1/story")
                        .header("Authorization", "Bearer " + tokenFor("rep2@dealco.example")))
                .andExpect(status().isNotFound());
    }

    // --- create, update, delete ----------------------------------------------------------------

    @Test
    void aRepCanCreateADealAndOwnsIt() throws Exception {
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealName":"Delta Deal","associatedContactId":"dl-c1",
                                 "pipelineId":"dl-pipe","currentStage":"Prospecting","dealValue":500.0,
                                 "expectedCloseDate":"2026-09-30","probabilityPercentage":40}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dealId").isNotEmpty())
                .andExpect(jsonPath("$.ownerId").value("dl-rep"))
                // Derived from the stage, never accepted from the caller.
                .andExpect(jsonPath("$.status").value("Open"));
    }

    @Test
    void creatingADealDirectlyInAWonStageSetsStatusAndProbability() throws Exception {
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealName":"Echo Deal","associatedContactId":"dl-c1",
                                 "pipelineId":"dl-pipe","currentStage":"Closed Won","dealValue":900.0,
                                 "expectedCloseDate":"2026-05-31","probabilityPercentage":10}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("Closed Won"))
                // The caller said 10; a won deal is certain, so the rule overrides them.
                .andExpect(jsonPath("$.probabilityPercentage").value(100));
    }

    @Test
    void aDealCanBeCreatedWithATeamAndTheTeamCanSeeIt() throws Exception {
        String body = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealName":"Foxtrot Deal","associatedContactId":"dl-c1",
                                 "pipelineId":"dl-pipe","currentStage":"Prospecting","dealValue":100.0,
                                 "expectedCloseDate":"2026-10-31","probabilityPercentage":20,
                                 "teamMemberIds":["dl-team"]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String dealId = objectMapper.readTree(body).get("dealId").asText();

        mockMvc.perform(get("/api/v1/deals/" + dealId)
                        .header("Authorization", "Bearer " + tokenFor("team@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team", hasSize(1)));
    }

    @Test
    void anExecutiveCannotCreateADeal() throws Exception {
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenFor("exec@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealName":"Golf Deal","associatedContactId":"dl-c1",
                                 "pipelineId":"dl-pipe","currentStage":"Prospecting","dealValue":100.0,
                                 "expectedCloseDate":"2026-11-30","probabilityPercentage":20}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void anInvalidProbabilityIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealName":"Hotel Deal","associatedContactId":"dl-c1",
                                 "pipelineId":"dl-pipe","currentStage":"Prospecting","dealValue":100.0,
                                 "expectedCloseDate":"2026-12-31","probabilityPercentage":140}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUpdateReplacesTheTeamWhenSuppliedAndLeavesItAloneWhenAbsent() throws Exception {
        // Absent field: the team must survive untouched. Treating null as "empty" here would strip
        // every deal's team on any update sent by a client that does not know the field exists.
        mockMvc.perform(put("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealName":"Alpha Deal","associatedContactId":"dl-c1",
                                 "pipelineId":"dl-pipe","dealValue":1000.0,
                                 "expectedCloseDate":"2026-06-30","probabilityPercentage":55}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(jsonPath("$.team", hasSize(1)));

        // Empty list: an explicit instruction to remove everyone, and a different request entirely.
        mockMvc.perform(put("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealName":"Alpha Deal","associatedContactId":"dl-c1",
                                 "pipelineId":"dl-pipe","dealValue":1000.0,
                                 "expectedCloseDate":"2026-06-30","probabilityPercentage":55,
                                 "teamMemberIds":[]}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(jsonPath("$.team", hasSize(0)));
    }

    @Test
    void aTeamMemberCanUpdateTheDealTheyAreOn() throws Exception {
        // V9 grants team members write access through the policy, so the code check must match.
        mockMvc.perform(put("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("team@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealName":"Alpha Deal Renamed","associatedContactId":"dl-c1",
                                 "pipelineId":"dl-pipe","dealValue":1000.0,
                                 "expectedCloseDate":"2026-06-30","probabilityPercentage":60}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealName").value("Alpha Deal Renamed"));
    }

    @Test
    void aRepCannotUpdateADealTheyHaveNoRouteTo() throws Exception {
        mockMvc.perform(put("/api/v1/deals/dl-d3")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dealName":"Hijacked","associatedContactId":"dl-c2",
                                 "pipelineId":"dl-pipe","dealValue":10.0,
                                 "expectedCloseDate":"2026-08-31","probabilityPercentage":10}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingADealAlsoRemovesItsActivities() throws Exception {
        // Activities have no foreign key to deals - they point at one through an untyped text pair -
        // so nothing cascades and they must be removed in code. Without that, this row survives
        // pointing at an id that no longer resolves.
        mockMvc.perform(delete("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(status().isNoContent());

        assertRowCount("SELECT count(*) FROM activities WHERE \"Parent_Object_ID\" = 'dl-d1'", 0);
        assertRowCount("SELECT count(*) FROM line_items WHERE \"Deal_ID\" = 'dl-d1'", 0);
        assertRowCount("SELECT count(*) FROM deal_team_members WHERE \"Deal_ID\" = 'dl-d1'", 0);
    }

    // --- line items ---------------------------------------------------------------------------

    @Test
    void addingALineItemRecalculatesTheDealValue() throws Exception {
        // The seed deal has one line worth 200 but a stored value of 1000 - deliberately
        // inconsistent, so that a passing assertion proves the recalculation ran rather than that
        // the number happened to already be right.
        mockMvc.perform(post("/api/v1/deals/dl-d1/line-items")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"dl-prod","quantity":3,"unitPriceAtSale":50.0,
                                 "discountAmount":25.0}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lineItems", hasSize(2)))
                // 3 x 50 - 25 = 125, computed server-side and never taken from the client.
                .andExpect(jsonPath("$.lineItems[1].totalLineValue").value(125.0))
                // 200 + 125, replacing the stale 1000.
                .andExpect(jsonPath("$.deal.dealValue").value(325.0));
    }

    @Test
    void updatingALineItemRecalculatesTheDealValue() throws Exception {
        mockMvc.perform(put("/api/v1/deals/dl-d1/line-items/dl-li1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"dl-prod","quantity":5,"unitPriceAtSale":100.0,
                                 "discountAmount":50.0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deal.dealValue").value(450.0));
    }

    @Test
    void removingTheLastLineItemLeavesTheDealValueAtZero() throws Exception {
        mockMvc.perform(delete("/api/v1/deals/dl-d1/line-items/dl-li1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineItems", hasSize(0)))
                .andExpect(jsonPath("$.deal.dealValue").value(0.0));
    }

    @Test
    void aLineItemCannotBeEditedThroughADealItDoesNotBelongTo() throws Exception {
        // The line_items policy authorises the row through its OWN deal and cannot know which deal
        // the caller named in the URL. Without the explicit check, a caller with access to dl-d2
        // could edit dl-d1's line item simply by putting the wrong id in the path.
        mockMvc.perform(put("/api/v1/deals/dl-d2/line-items/dl-li1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"dl-prod","quantity":1,"unitPriceAtSale":1.0,
                                 "discountAmount":0.0}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void aZeroQuantityLineItemIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/deals/dl-d1/line-items")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"dl-prod","quantity":0,"unitPriceAtSale":50.0,
                                 "discountAmount":0.0}"""))
                .andExpect(status().isBadRequest());
    }

    // --- contacts on a deal ---------------------------------------------------------------------

    @Test
    void aContactCanBeAttachedAndDetached() throws Exception {
        mockMvc.perform(post("/api/v1/deals/dl-d1/contacts")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactId":"dl-c2","primary":false}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contactId").value("dl-c2"));

        mockMvc.perform(get("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(jsonPath("$.contacts", hasSize(2)));

        mockMvc.perform(delete("/api/v1/deals/dl-d1/contacts/dl-c2")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/deals/dl-d1")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(jsonPath("$.contacts", hasSize(1)));
    }

    @Test
    void detachingAContactThatIsNotAttachedIsNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/deals/dl-d1/contacts/dl-c2")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example")))
                .andExpect(status().isNotFound());
    }

    // --- stage transitions -----------------------------------------------------------------------

    @Test
    void movingToClosedWonSetsStatusAndProbabilityAutomatically() throws Exception {
        mockMvc.perform(put("/api/v1/deals/dl-d1/stage")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentStage":"Closed Won"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("Closed Won"))
                .andExpect(jsonPath("$.status").value("Closed Won"))
                .andExpect(jsonPath("$.probabilityPercentage").value(100));
    }

    @Test
    void wonDetectionIgnoresCasingAndPunctuation() throws Exception {
        // Stage names are free text an administrator types per pipeline. Exact matching fails
        // silently here: the deal moves, no error appears, and the revenue never counts.
        mockMvc.perform(put("/api/v1/deals/dl-d1/stage")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentStage":"closed-won"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Closed Won"))
                .andExpect(jsonPath("$.probabilityPercentage").value(100));
    }

    @Test
    void movingToANonTerminalStageLeavesTheCallersProbabilityAlone() throws Exception {
        mockMvc.perform(put("/api/v1/deals/dl-d1/stage")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentStage":"Negotiation"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Open"))
                // Unchanged from the seed - the rule has no opinion about non-terminal stages.
                .andExpect(jsonPath("$.probabilityPercentage").value(50));
    }

    @Test
    void markingADealLostRecordsTheReason() throws Exception {
        mockMvc.perform(put("/api/v1/deals/dl-d1/lost")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"winLossReason":"Lost on price to a competitor"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Closed Lost"))
                .andExpect(jsonPath("$.probabilityPercentage").value(0))
                .andExpect(jsonPath("$.winLossReason").value("Lost on price to a competitor"));
    }

    @Test
    void markingADealLostWithoutAReasonIsRejected() throws Exception {
        // A lost deal with no recorded reason is the one audit record nobody can reconstruct later.
        mockMvc.perform(put("/api/v1/deals/dl-d1/lost")
                        .header("Authorization", "Bearer " + tokenFor("rep@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"winLossReason":"  "}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anExecutiveCannotMoveADealsStage() throws Exception {
        mockMvc.perform(put("/api/v1/deals/dl-d1/stage")
                        .header("Authorization", "Bearer " + tokenFor("exec@dealco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentStage":"Closed Won"}"""))
                .andExpect(status().isForbidden());
    }

    // --- auth ------------------------------------------------------------------------------------

    @Test
    void aRequestWithNoTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/deals"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---------------------------------------------------------------------------------

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
