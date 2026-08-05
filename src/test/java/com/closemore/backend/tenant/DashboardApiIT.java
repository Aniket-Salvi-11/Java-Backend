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
 * The five dashboard aggregates over real HTTP.
 *
 * <p><b>Every test here asserts a NUMBER, which is why there are so many of them.</b> A broken
 * aggregate does not throw - it returns a plausible figure, and a rep seeing their team's pipeline
 * total instead of their own has no way to tell. So the seed below is built from values that make
 * each scoping mistake produce a visibly different answer: rep-owned deals total 1000, rep2-owned
 * total 500, and no sum of the wrong subset coincidentally equals the right one.
 *
 * <p>Organisation "Dashco". Rivalco exists to prove the aggregates stop at the tenant boundary.
 */
class DashboardApiIT extends AbstractWebIT {

    private static final String PASSWORD = "dashco-password";

    private static final String STAGES = """
            [{"name":"Discovery","order":1},{"name":"Proposal","order":2},{"name":"Closed Won","order":3}]""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedDashboardData() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("DELETE FROM activities WHERE \"Log_ID\" LIKE 'db-%'");
                stmt.execute("DELETE FROM deals WHERE \"Deal_ID\" LIKE 'db-%'");
                stmt.execute("DELETE FROM contacts WHERE \"Contact_ID\" LIKE 'db-%'");
                stmt.execute("DELETE FROM pipelines WHERE \"Pipeline_ID\" LIKE 'db-%'");
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users
                            WHERE "Organization_Name" IN ('Dashco','Dashrival'))
                        """);
                stmt.execute(
                        "DELETE FROM users WHERE \"Organization_Name\" IN ('Dashco','Dashrival')");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('db-admin','Dee','Admin','admin@dashco.example','Admin','Active','Dashco','%s'),
                          ('db-exec','Ed','Exec','exec@dashco.example','Executive','Active','Dashco','%s'),
                          ('db-rep','Rita','Rep','rep@dashco.example','Sales_Rep','Active','Dashco','%s'),
                          ('db-rep2','Raj','Rep2','rep2@dashco.example','Sales_Rep','Active','Dashco','%s'),
                          ('db-rival','Rob','Rival','rival@dashrival.example','Admin','Active','Dashrival','%s')
                        """.formatted(PASSWORD, PASSWORD, PASSWORD, PASSWORD, PASSWORD));
                stmt.execute("""
                        INSERT INTO pipelines ("Pipeline_ID","Pipeline_Name","Stages_JSON")
                        VALUES ('db-pipe','Dashco Pipeline','%s'::jsonb)
                        """.formatted(STAGES));
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES
                          ('db-c1','Dash','Client','dash@buyer.example','555-4001','Buyer Ltd',
                           'Lead','Test','2026-01-01','db-rep'),
                          ('db-c2','Rival','Client','rival@buyer.example','555-4002','Rival Buyer',
                           'Lead','Test','2026-01-01','db-rival')
                        """);
                // rep: 1 open 1000 at 50%, 1 won 400, 1 lost 200
                // rep2: 1 open 500 at 20%, 1 won 100
                // Deliberately distinct totals - see the class javadoc.
                stmt.execute("""
                        INSERT INTO deals ("Deal_ID","Deal_Name","Associated_Contact_ID","Pipeline_ID",
                          "Current_Stage","Deal_Value","Expected_Close_Date","Probability_Percentage",
                          "Owner_ID","Status","ARR","TCV","TLV","Commission","Partner_Commission",
                          "Distributor_Commission")
                        VALUES
                          ('db-open1','Rep Open','db-c1','db-pipe','Discovery',1000.0,'2026-06-30',50,
                           'db-rep','Open',0,0,0,0,0,0),
                          ('db-won1','Rep Won','db-c1','db-pipe','Closed Won',400.0,'2026-05-31',100,
                           'db-rep','Closed Won',0,0,0,0,0,0),
                          ('db-lost1','Rep Lost','db-c1','db-pipe','Closed Lost',200.0,'2026-05-31',0,
                           'db-rep','Closed Lost',0,0,0,0,0,0),
                          ('db-open2','Rep2 Open','db-c1','db-pipe','Proposal',500.0,'2026-07-31',20,
                           'db-rep2','Open',0,0,0,0,0,0),
                          ('db-won2','Rep2 Won','db-c1','db-pipe','Closed Won',100.0,'2026-04-30',100,
                           'db-rep2','Closed Won',0,0,0,0,0,0),
                          ('db-rival1','Rival Won','db-c2','db-pipe','Closed Won',99999.0,'2026-05-31',100,
                           'db-rival','Closed Won',0,0,0,0,0,0)
                        """);
                stmt.execute("""
                        INSERT INTO activities ("Log_ID","Parent_Object_Type","Parent_Object_ID",
                          "Activity_Type","Summary","Detailed_Description","Log_Date","Logged_By_User_ID")
                        VALUES
                          ('db-a1','Deal','db-open1','Note','Rep note one','Detail','2026-01-01','db-rep'),
                          ('db-a2','Deal','db-open1','Note','Rep note two','Detail','2026-02-01','db-rep'),
                          ('db-a3','Deal','db-open2','Note','Rep2 note','Detail','2026-03-01','db-rep2')
                        """);
            }
            connection.commit();
        }
    }

    // --- revenue -----------------------------------------------------------------------------

    @Test
    void revenueForAnAdminCoversTheWholeOrganisation() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/revenue")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wonCount").value(2))
                .andExpect(jsonPath("$.wonValue").value(500.0))
                .andExpect(jsonPath("$.lostCount").value(1))
                .andExpect(jsonPath("$.lostValue").value(200.0));
    }

    @Test
    void revenueForARepCoversOnlyTheirOwnDeals() throws Exception {
        // 400, not 500. If the owner filter were missing this would silently return the admin's
        // number and look perfectly reasonable.
        mockMvc.perform(get("/api/v1/dashboard/revenue")
                        .header("Authorization", "Bearer " + tokenFor("rep@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wonCount").value(1))
                .andExpect(jsonPath("$.wonValue").value(400.0))
                .andExpect(jsonPath("$.lostValue").value(200.0));
    }

    @Test
    void anExecutiveSeesTheWholeOrganisationLikeAnAdmin() throws Exception {
        // canViewAll covers Executive as well as Admin - the read side, unlike writes where
        // blockExecutiveWrites applies.
        mockMvc.perform(get("/api/v1/dashboard/revenue")
                        .header("Authorization", "Bearer " + tokenFor("exec@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wonValue").value(500.0));
    }

    @Test
    void revenueStopsAtTheTenantBoundary() throws Exception {
        // Dashrival's won deal is worth 99999. If RLS were not scoping these queries the admin's
        // total would be unmistakably wrong rather than subtly so - which is the point of the value.
        mockMvc.perform(get("/api/v1/dashboard/revenue")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wonValue").value(500.0));
    }

    // --- forecast ----------------------------------------------------------------------------

    @Test
    void forecastCountsOnlyOpenDeals() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/forecast")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openCount").value(2))
                .andExpect(jsonPath("$.openValue").value(1500.0));
    }

    @Test
    void forecastReportsTheProbabilityWeightedTotalToo() throws Exception {
        // 1000 at 50% plus 500 at 20% = 600. Raw and weighted differ by a lot, which is exactly why
        // both are returned rather than one being guessed at - see ForecastResponse.
        mockMvc.perform(get("/api/v1/dashboard/forecast")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightedValue").value(600.0));
    }

    @Test
    void forecastForARepIsOwnerScoped() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/forecast")
                        .header("Authorization", "Bearer " + tokenFor("rep@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openCount").value(1))
                .andExpect(jsonPath("$.openValue").value(1000.0))
                .andExpect(jsonPath("$.weightedValue").value(500.0));
    }

    // --- pipeline ----------------------------------------------------------------------------

    @Test
    void thePipelineBreakdownGroupsOpenDealsByStage() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/pipeline")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // Indices, not a JsonPath filter: a filter expression evaluates to a LIST, and
                // .value(1000.0) against a list never matches. The service sorts by stage name, so
                // Discovery precedes Proposal.
                .andExpect(jsonPath("$[0].stage").value("Discovery"))
                .andExpect(jsonPath("$[0].dealCount").value(1))
                .andExpect(jsonPath("$[0].totalValue").value(1000.0))
                .andExpect(jsonPath("$[1].stage").value("Proposal"))
                .andExpect(jsonPath("$[1].totalValue").value(500.0));
    }

    @Test
    void thePipelineBreakdownExcludesClosedDeals() throws Exception {
        // Closed Won and Closed Lost deals exist on this pipeline. Including them would
        // double-count against the revenue endpoint and make the pipeline look permanently full.
        mockMvc.perform(get("/api/v1/dashboard/pipeline")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.stage == 'Closed Won')]", hasSize(0)))
                .andExpect(jsonPath("$[?(@.stage == 'Closed Lost')]", hasSize(0)));
    }

    @Test
    void thePipelineBreakdownIsOwnerScoped() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/pipeline")
                        .header("Authorization", "Bearer " + tokenFor("rep@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].stage").value("Discovery"));
    }

    // --- leaderboard -------------------------------------------------------------------------

    @Test
    void theLeaderboardRanksEveryRepEvenForARep() throws Exception {
        // The one endpoint here that is NOT owner-scoped. A leaderboard of one person is not a
        // leaderboard, and publishing standings is the entire purpose.
        mockMvc.perform(get("/api/v1/dashboard/leaderboard")
                        .header("Authorization", "Bearer " + tokenFor("rep@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].ownerId").value("db-rep"))
                .andExpect(jsonPath("$[0].wonValue").value(400.0))
                .andExpect(jsonPath("$[1].ownerId").value("db-rep2"));
    }

    @Test
    void theLeaderboardResolvesOwnerNames() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/leaderboard")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ownerName").value("Rita Rep"));
    }

    @Test
    void theLeaderboardStopsAtTheTenantBoundary() throws Exception {
        // Dashrival's admin has the single largest won deal in the database. RLS keeps them off.
        mockMvc.perform(get("/api/v1/dashboard/leaderboard")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ownerId == 'db-rival')]", hasSize(0)));
    }

    @Test
    void anUnknownTimeframeIsRejected() throws Exception {
        // A 400 rather than a silent fallback to all time: a caller asking for "week" and getting
        // every deal ever closed would have no way to notice.
        mockMvc.perform(get("/api/v1/dashboard/leaderboard?timeframe=week")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMonthTimeframeIsAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/leaderboard?timeframe=month")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void aQuarterTimeframeIsAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/leaderboard?timeframe=quarter")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // --- recent activity ---------------------------------------------------------------------

    @Test
    void recentActivityForARepShowsOnlyTheirOwn() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/recent-activity")
                        .header("Authorization", "Bearer " + tokenFor("rep@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.logId == 'db-a3')]", hasSize(0)));
    }

    @Test
    void recentActivityIsNewestFirst() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/recent-activity")
                        .header("Authorization", "Bearer " + tokenFor("rep@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].logId").value("db-a2"));
    }

    @Test
    void recentActivityForAnAdminCoversTheOrganisation() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/recent-activity")
                        .header("Authorization", "Bearer " + tokenFor("admin@dashco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.logId == 'db-a3')]", hasSize(1)));
    }

    // --- auth --------------------------------------------------------------------------------

    @Test
    void theDashboardRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/revenue"))
                .andExpect(status().isUnauthorized());
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
}
