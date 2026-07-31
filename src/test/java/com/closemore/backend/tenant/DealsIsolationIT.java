package com.closemore.backend.tenant;

import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.LineItemEntity;
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
 * The first DEEP-POLICY isolation test, and the reason deals/line_items were worth doing early.
 *
 * <p>{@code RepositoryTenantIsolationIT} proves RLS survives Hibernate for contacts, whose policy
 * is a single EXISTS against users on a direct Owner_ID. line_items is a different animal: it has
 * no tenant column and no owner column, and its policy joins through deals to users before it can
 * decide anything. Two hops, and nothing on the row itself says who may see it.
 *
 * <p>Two-hop policies are the ones most likely to be quietly wrong, because when they fail they
 * return an empty list rather than an error - indistinguishable at a glance from "no data yet".
 *
 * <p><b>Uses its own organisation, "Initech", with three users.</b> The middle case below needs a
 * second Sales_Rep in the same tenant who does NOT own the deal, and seeding one into Acme would
 * break {@code theUserDirectoryIsTenantScoped} (which expects exactly one Acme user) in a different
 * file. Acme and Globex counts are untouched by anything here.
 */
class DealsIsolationIT extends AbstractRlsIT {

    @Autowired
    DealsReadService readService;

    /** Runs after AbstractRlsIT#seedTwoTenants(); superclass @BeforeEach methods go first. */
    @BeforeEach
    void seedInitechDeal() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status","Organization_Name")
                        VALUES
                          ('init-admin','Ini','Admin','init-admin@example.com','Admin','Active','Initech'),
                          ('init-rep','Ini','Rep','init-rep@example.com','Sales_Rep','Active','Initech'),
                          ('init-other','Ini','Other','init-other@example.com','Sales_Rep','Active','Initech')
                        ON CONFLICT ("User_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES ('contact-init','Ini','Client','init-client@example.com','555-2001',
                                'Initech Client','Lead','Test','2026-01-01','init-rep')
                        ON CONFLICT ("Contact_ID") DO NOTHING
                        """);
                // FK prerequisites for a deal and a line item.
                stmt.execute("""
                        INSERT INTO pipelines ("Pipeline_ID","Pipeline_Name","Stages_JSON")
                        VALUES ('pipe-deals','Deals Test Pipeline','[]'::jsonb)
                        ON CONFLICT ("Pipeline_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO products ("Product_ID","Name","SKU_Code","Type","Unit_Price","Description","Is_Active")
                        VALUES ('prod-deals','Deals Test Product','SKU-DEALS','Hardware',199.99,'d',true)
                        ON CONFLICT ("Product_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO deals ("Deal_ID","Deal_Name","Associated_Contact_ID","Pipeline_ID",
                          "Current_Stage","Deal_Value","Expected_Close_Date","Probability_Percentage",
                          "Owner_ID","Status","ARR","TCV","TLV","Commission","Partner_Commission",
                          "Distributor_Commission")
                        VALUES ('deal-init','Initech Deal','contact-init','pipe-deals','Proposal',
                                50000,'2026-06-30',60,'init-rep','Open',
                                12000,50000,60000,5000,1000,500)
                        ON CONFLICT ("Deal_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO line_items ("Line_Item_ID","Deal_ID","Product_ID","Quantity",
                          "Unit_Price_At_Sale","Discount_Amount","Total_Line_Value")
                        VALUES ('li-init','deal-init','prod-deals',10,199.99,99.90,1899.00)
                        ON CONFLICT ("Line_Item_ID") DO NOTHING
                        """);
            }
            connection.commit();
        }
    }

    @Test
    void theDealOwnerSeesTheirDealAndItsLineItems() {
        List<DealEntity> deals = asTenant("init-rep", "Sales_Rep", "Initech",
                () -> readService.allDeals());
        List<LineItemEntity> items = asTenant("init-rep", "Sales_Rep", "Initech",
                () -> readService.allLineItems());

        assertThat(deals).extracting(DealEntity::getDealId).contains("deal-init");
        assertThat(items).extracting(LineItemEntity::getLineItemId).contains("li-init");
    }

    /**
     * The case that only a two-hop policy can get wrong: right organisation, wrong owner. The line
     * item carries no owner of its own, so if the policy failed to walk through deals it would be
     * visible here.
     */
    @Test
    void aSalesRepInTheSameOrganisationWhoDoesNotOwnTheDealSeesNeither() {
        List<DealEntity> deals = asTenant("init-other", "Sales_Rep", "Initech",
                () -> readService.allDeals());
        List<LineItemEntity> items = asTenant("init-other", "Sales_Rep", "Initech",
                () -> readService.allLineItems());

        assertThat(deals).extracting(DealEntity::getDealId).doesNotContain("deal-init");
        assertThat(items).extracting(LineItemEntity::getLineItemId).doesNotContain("li-init");
    }

    @Test
    void anAdminInTheSameOrganisationSeesBoth() {
        List<DealEntity> deals = asTenant("init-admin", "Admin", "Initech",
                () -> readService.allDeals());
        List<LineItemEntity> items = asTenant("init-admin", "Admin", "Initech",
                () -> readService.allLineItems());

        assertThat(deals).extracting(DealEntity::getDealId).contains("deal-init");
        assertThat(items).extracting(LineItemEntity::getLineItemId).contains("li-init");
    }

    @Test
    void anAdminInAnotherOrganisationSeesNeither() {
        List<DealEntity> deals = asTenant("user-a", "Admin", "Acme",
                () -> readService.allDeals());
        List<LineItemEntity> items = asTenant("user-a", "Admin", "Acme",
                () -> readService.allLineItems());

        assertThat(deals).extracting(DealEntity::getDealId).doesNotContain("deal-init");
        assertThat(items).extracting(LineItemEntity::getLineItemId).doesNotContain("li-init");
    }

    @Test
    void findByDealIdIsAlsoFilteredNotJustFindAll() {
        // A derived query builds different SQL from findAll(). RLS applies to both, but asserting
        // it means a future @Query with a hand-written join cannot quietly skip the policy.
        List<LineItemEntity> asOwner = asTenant("init-rep", "Sales_Rep", "Initech",
                () -> readService.lineItemsForDeal("deal-init"));
        List<LineItemEntity> asOutsider = asTenant("user-a", "Admin", "Acme",
                () -> readService.lineItemsForDeal("deal-init"));

        assertThat(asOwner).hasSize(1);
        assertThat(asOutsider).isEmpty();
    }

    @Test
    void allTwentyDealColumnsMapIncludingTheV7FinancialFields() {
        DealEntity deal = asTenant("init-rep", "Sales_Rep", "Initech",
                () -> readService.dealById("deal-init")).orElseThrow();

        assertThat(deal.getDealName()).isEqualTo("Initech Deal");
        assertThat(deal.getAssociatedContactId()).isEqualTo("contact-init");
        assertThat(deal.getPipelineId()).isEqualTo("pipe-deals");
        assertThat(deal.getCurrentStage()).isEqualTo("Proposal");
        assertThat(deal.getDealValue()).isEqualTo(50000.0);
        assertThat(deal.getExpectedCloseDate()).isEqualTo("2026-06-30");
        assertThat(deal.getProbabilityPercentage()).isEqualTo(60);
        assertThat(deal.getWinLossReason()).isNull();
        assertThat(deal.getStatus()).isEqualTo("Open");
        assertThat(deal.getDealSource()).isNull();
        assertThat(deal.getCreatedAt()).isNotNull();
        assertThat(deal.getUpdatedAt()).isNotNull();
        assertThat(deal.getArr()).isEqualTo(12000.0);
        assertThat(deal.getTcv()).isEqualTo(50000.0);
        assertThat(deal.getTlv()).isEqualTo(60000.0);
        assertThat(deal.getCommission()).isEqualTo(5000.0);
        assertThat(deal.getPartnerCommission()).isEqualTo(1000.0);
        assertThat(deal.getDistributorCommission()).isEqualTo(500.0);
    }

    @Test
    void lineItemColumnsMapIncludingTheNumericFields() {
        LineItemEntity item = asTenant("init-rep", "Sales_Rep", "Initech",
                () -> readService.lineItemsForDeal("deal-init")).get(0);

        assertThat(item.getLineItemId()).isEqualTo("li-init");
        assertThat(item.getProductId()).isEqualTo("prod-deals");
        assertThat(item.getQuantity()).isEqualTo(10);
        assertThat(item.getUnitPriceAtSale()).isEqualTo(199.99);
        assertThat(item.getDiscountAmount()).isEqualTo(99.90);
        assertThat(item.getTotalLineValue()).isEqualTo(1899.00);
    }
}
