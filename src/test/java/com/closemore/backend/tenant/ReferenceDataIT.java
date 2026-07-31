package com.closemore.backend.tenant;

import com.closemore.backend.domain.PipelineEntity;
import com.closemore.backend.domain.ProductEntity;
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
 * Covers the first two Phase 1 entities beyond users/contacts: products and pipelines.
 *
 * <p>Worth knowing what this test adds beyond {@code ddl-auto: validate}. Validation already fails
 * the whole context at startup if a column name or type is wrong, so every other IT in the suite
 * would go red too - that safety net is free and needs no test. What validation does NOT prove is
 * that a value actually round-trips, and for {@code pipelines.Stages_JSON} that is the open
 * question: it is the first JSONB column mapped in this codebase, and {@code @JdbcTypeCode} could
 * satisfy the validator while still failing to read.
 *
 * <p>These two tables are also the first with NO RLS policy - global reference data by design - so
 * this is where that decision gets asserted rather than assumed. If someone later adds a policy to
 * either table, {@code referenceDataIsReadableWithNoTenantContextAtAll} fails and forces the
 * conversation.
 */
class ReferenceDataIT extends AbstractRlsIT {

    private static final String STAGES = """
            [{"name":"Qualification","order":1},{"name":"Proposal","order":2},{"name":"Closed Won","order":3}]""";

    @Autowired
    ReferenceDataReadService readService;

    @BeforeEach
    void seedReferenceData() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        INSERT INTO products ("Product_ID","Name","SKU_Code","Type","Unit_Price","Description","Is_Active")
                        VALUES
                          ('prod-live','Live Widget','SKU-LIVE','Hardware',199.99,'A widget that ships',true),
                          ('prod-retired','Retired Widget','SKU-OLD','Hardware',49.50,'No longer sold',false)
                        ON CONFLICT ("Product_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO pipelines ("Pipeline_ID","Pipeline_Name","Stages_JSON")
                        VALUES ('pipe-standard','Standard Sales Pipeline',
                                '%s'::jsonb)
                        ON CONFLICT ("Pipeline_ID") DO NOTHING
                        """.formatted(STAGES));
            }
            connection.commit();
        }
    }

    @Test
    void productsMapEveryColumnIncludingNumericAndBoolean() {
        ProductEntity product = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.allProducts()).stream()
                .filter(p -> "prod-live".equals(p.getProductId()))
                .findFirst()
                .orElseThrow();

        assertThat(product.getName()).isEqualTo("Live Widget");
        assertThat(product.getSkuCode()).isEqualTo("SKU-LIVE");
        assertThat(product.getType()).isEqualTo("Hardware");
        assertThat(product.getUnitPrice()).isEqualTo(199.99);
        assertThat(product.getDescription()).isEqualTo("A widget that ships");
        assertThat(product.isActive()).isTrue();
    }

    @Test
    void theDerivedBooleanQueryResolvesTheRightProperty() {
        // Guards the ProductEntity naming note: had the field been called isActive, this query
        // would fail at context startup with "No property 'isActive' found for type ProductEntity".
        List<ProductEntity> active = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.activeProducts());

        assertThat(active).extracting(ProductEntity::getProductId)
                .contains("prod-live")
                .doesNotContain("prod-retired");
    }

    /** The actual point of this class: proving the JSONB mapping reads, not just validates. */
    @Test
    void theJsonbColumnRoundTripsAsRawJsonText() {
        PipelineEntity pipeline = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.pipelineById("pipe-standard")).orElseThrow();

        assertThat(pipeline.getPipelineName()).isEqualTo("Standard Sales Pipeline");
        assertThat(pipeline.getStagesJson())
                .as("@JdbcTypeCode(SqlTypes.JSON) on a String field must return the raw document")
                .contains("Qualification")
                .contains("Closed Won")
                .startsWith("[");
    }

    @Test
    void referenceDataIsTheSameForEveryTenant() {
        List<String> asAcme = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.allProducts()).stream().map(ProductEntity::getProductId).toList();
        List<String> asGlobex = asTenant("user-b", "Sales_Rep", "Globex",
                () -> readService.allProducts()).stream().map(ProductEntity::getProductId).toList();

        assertThat(asAcme).containsExactlyInAnyOrderElementsOf(asGlobex);
        assertThat(asAcme).contains("prod-live", "prod-retired");
    }

    @Test
    void referenceDataIsReadableWithNoTenantContextAtAll() {
        contextHolder.clear();

        // Deliberate contrast with contacts/users, which return nothing here. These two tables have
        // no RLS by design. If this starts failing, someone added a policy - decide whether that was
        // intended before "fixing" the test.
        assertThat(readService.allProducts()).isNotEmpty();
        assertThat(readService.allPipelines()).isNotEmpty();
    }
}
