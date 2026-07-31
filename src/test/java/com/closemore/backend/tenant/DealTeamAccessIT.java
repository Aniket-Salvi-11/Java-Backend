package com.closemore.backend.tenant;

import com.closemore.backend.domain.DealContactEntity;
import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.DealTeamMemberEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two jobs: prove composite-key entities work, and cover the team-membership access path, which
 * until now was completely untested.
 *
 * <p><b>The important discovery this test encodes.</b> V9__deal_teams.sql DROPS and RECREATES the
 * policies for deals, line_items and deal_contacts. Reading V4 gives a wrong picture: it suggests
 * only the owner plus Admin/Executive can see a deal, when the live policy adds a third route -
 * membership in deal_team_members. {@code DealsIsolationIT} passes today only because its
 * non-owner Sales_Rep happens not to be on the team; that is a narrower guarantee than its name
 * suggests, and the two tests here pin the difference.
 *
 * <p>Uses the Initech organisation and two deals, so Acme and Globex counts asserted elsewhere are
 * untouched:
 * <ul>
 *   <li>{@code deal-solo} - owned by init-rep, nobody else on the team</li>
 *   <li>{@code deal-team} - owned by init-rep, with init-other added as a team member</li>
 * </ul>
 */
class DealTeamAccessIT extends AbstractRlsIT {

    @Autowired
    DealTeamReadService readService;

    @BeforeEach
    void seedTeamFixtures() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status","Organization_Name")
                        VALUES
                          ('team-owner','Team','Owner','team-owner@example.com','Sales_Rep','Active','Initech'),
                          ('team-member','Team','Member','team-member@example.com','Sales_Rep','Active','Initech'),
                          ('team-outsider','Team','Outsider','team-outsider@example.com','Sales_Rep','Active','Initech')
                        ON CONFLICT ("User_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES ('contact-team','Team','Client','team-client@example.com','555-3001',
                                'Initech Team Client','Lead','Test','2026-01-01','team-owner')
                        ON CONFLICT ("Contact_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO pipelines ("Pipeline_ID","Pipeline_Name","Stages_JSON")
                        VALUES ('pipe-team','Team Test Pipeline','[]'::jsonb)
                        ON CONFLICT ("Pipeline_ID") DO NOTHING
                        """);
                for (String dealId : new String[]{"deal-solo", "deal-team"}) {
                    stmt.execute("""
                            INSERT INTO deals ("Deal_ID","Deal_Name","Associated_Contact_ID","Pipeline_ID",
                              "Current_Stage","Deal_Value","Expected_Close_Date","Probability_Percentage",
                              "Owner_ID","Status","ARR","TCV","TLV","Commission","Partner_Commission",
                              "Distributor_Commission")
                            VALUES ('%s','%s','contact-team','pipe-team','Proposal',1000,'2026-06-30',50,
                                    'team-owner','Open',0,0,0,0,0,0)
                            ON CONFLICT ("Deal_ID") DO NOTHING
                            """.formatted(dealId, dealId));
                    stmt.execute("""
                            INSERT INTO deal_contacts ("Deal_ID","Contact_ID","Is_Primary")
                            VALUES ('%s','contact-team',true)
                            ON CONFLICT ("Deal_ID","Contact_ID") DO NOTHING
                            """.formatted(dealId));
                }
                stmt.execute("""
                        INSERT INTO deal_team_members ("Deal_ID","User_ID")
                        VALUES ('deal-team','team-member')
                        ON CONFLICT ("Deal_ID","User_ID") DO NOTHING
                        """);
            }
            connection.commit();
        }
    }

    // ---------- composite keys ----------

    @Test
    void findByIdRoundTripsThroughTheCompositeKey() {
        DealContactEntity link = asTenant("team-owner", "Sales_Rep", "Initech",
                () -> readService.dealContactById("deal-solo", "contact-team")).orElseThrow();

        assertThat(link.getId().getDealId()).isEqualTo("deal-solo");
        assertThat(link.getId().getContactId()).isEqualTo("contact-team");
        assertThat(link.isPrimary()).isTrue();
    }

    @Test
    void theUnderscoreDerivedQueryReachesIntoTheEmbeddedKey() {
        // findById_DealId means the property path id.dealId. Without the underscore Spring Data
        // has to guess the property boundary, and this is exactly the shape it guesses wrong on.
        List<DealContactEntity> links = asTenant("team-owner", "Sales_Rep", "Initech",
                () -> readService.dealContactsFor("deal-team"));

        assertThat(links).hasSize(1);
        assertThat(links.get(0).getId().getContactId()).isEqualTo("contact-team");
    }

    // ---------- the team-membership access path ----------

    @Test
    void aTeamMemberCanSeeADealTheyDoNotOwn() {
        List<DealEntity> deals = asTenant("team-member", "Sales_Rep", "Initech",
                () -> readService.allDeals());

        assertThat(deals).extracting(DealEntity::getDealId)
                .as("V9 added deal_team_members as a third route to read access")
                .contains("deal-team")
                .doesNotContain("deal-solo");
    }

    @Test
    void teamMembershipAlsoUnlocksTheDealsContacts() {
        List<DealContactEntity> visible = asTenant("team-member", "Sales_Rep", "Initech",
                () -> readService.allDealContacts());

        assertThat(visible).extracting(l -> l.getId().getDealId())
                .contains("deal-team")
                .doesNotContain("deal-solo");
    }

    @Test
    void aNonMemberNonOwnerInTheSameOrganisationSeesNeitherDeal() {
        List<DealEntity> deals = asTenant("team-outsider", "Sales_Rep", "Initech",
                () -> readService.allDeals());

        assertThat(deals).extracting(DealEntity::getDealId)
                .doesNotContain("deal-solo", "deal-team");
    }

    @Test
    void teamMembershipDoesNotCrossOrganisations() {
        List<DealEntity> deals = asTenant("user-a", "Admin", "Acme",
                () -> readService.allDeals());
        List<DealTeamMemberEntity> members = asTenant("user-a", "Admin", "Acme",
                () -> readService.allTeamMembers());

        assertThat(deals).extracting(DealEntity::getDealId).doesNotContain("deal-solo", "deal-team");
        assertThat(members).extracting(m -> m.getId().getDealId()).doesNotContain("deal-team");
    }

    /**
     * Documents a real asymmetry rather than asserting an ideal. deal_team_members' own policy
     * checks only that the MEMBER shares the caller's organisation - no owner or role condition -
     * so a colleague who cannot open deal-team can still see who is on it. Matches the JS original;
     * flagged in DealTeamMemberEntity. If this test starts failing, someone tightened the policy,
     * which is probably good but is a deliberate behaviour change.
     */
    @Test
    void teamMembershipRowsAreVisibleToAnyoneInTheSameOrganisation() {
        List<DealTeamMemberEntity> members = asTenant("team-outsider", "Sales_Rep", "Initech",
                () -> readService.teamMembersFor("deal-team"));

        assertThat(members).extracting(m -> m.getId().getUserId()).contains("team-member");
    }
}
