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
 * The product catalogue over real HTTP.
 *
 * <p><b>Assertions here never count the whole catalogue.</b> Products are global reference data
 * with no RLS, so rows seeded by ReferenceDataIT and DealApiIT are visible from here too and their
 * number is not this class's business. Every assertion names specific product ids instead - which
 * is also the honest way to test a table three other classes write to.
 */
class ProductApiIT extends AbstractWebIT {

    private static final String PASSWORD = "prodco-password";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seedCatalogue() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("DELETE FROM events_log WHERE \"Object_Type\" = 'Product'");
                stmt.execute("DELETE FROM products WHERE \"Product_ID\" LIKE 'pc-%'");
                stmt.execute("""
                        DELETE FROM refresh_tokens WHERE "User_ID" IN
                          (SELECT "User_ID" FROM users WHERE "Organization_Name" = 'Prodco')
                        """);
                stmt.execute("DELETE FROM users WHERE \"Organization_Name\" = 'Prodco'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                          "Organization_Name","Password")
                        VALUES
                          ('pc-admin','Pc','Admin','admin@prodco.example','Admin','Active','Prodco','%s'),
                          ('pc-rep','Pc','Rep','rep@prodco.example','Sales_Rep','Active','Prodco','%s')
                        """.formatted(PASSWORD, PASSWORD));
                stmt.execute("""
                        INSERT INTO products ("Product_ID","Name","SKU_Code","Type","Unit_Price",
                          "Description","Is_Active")
                        VALUES
                          ('pc-live','Zeta Live Widget','SKU-PC-LIVE','Hardware',100.0,'On sale',true),
                          ('pc-dead','Zeta Dead Widget','SKU-PC-DEAD','Hardware',50.0,'Retired',false)
                        """);
            }
            connection.commit();
        }
    }

    // --- reads ------------------------------------------------------------------------------

    @Test
    void theCatalogueIsReadableByANonAdmin() throws Exception {
        // Reads are open on purpose: the deal line-item flow needs the catalogue to price anything,
        // and a Sales_Rep is exactly who builds a deal.
        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor("rep@prodco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.productId == 'pc-live')]", hasSize(1)));
    }

    @Test
    void theListIncludesRetiredProductsByDefault() throws Exception {
        // A retired product still appears on historical line items, so a client rendering an old
        // deal has to be able to resolve it.
        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor("rep@prodco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productId == 'pc-dead')]", hasSize(1)));
    }

    @Test
    void activeOnlyExcludesRetiredProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products?activeOnly=true")
                        .header("Authorization", "Bearer " + tokenFor("rep@prodco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productId == 'pc-live')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.productId == 'pc-dead')]", hasSize(0)));
    }

    @Test
    void addingPageSwitchesToTheEnvelope() throws Exception {
        // "items", not "content" - PageResponse is this project's own record.
        mockMvc.perform(get("/api/v1/products?page=0&size=2")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void aSingleProductIsReadableById() throws Exception {
        mockMvc.perform(get("/api/v1/products/pc-live")
                        .header("Authorization", "Bearer " + tokenFor("rep@prodco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuCode").value("SKU-PC-LIVE"))
                .andExpect(jsonPath("$.unitPrice").value(100.0))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void anUnknownProductIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/products/pc-nope")
                        .header("Authorization", "Bearer " + tokenFor("rep@prodco.example")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownSortColumnIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/products?sort=description")
                        .header("Authorization", "Bearer " + tokenFor("rep@prodco.example")))
                .andExpect(status().isBadRequest());
    }

    // --- writes -----------------------------------------------------------------------------

    @Test
    void anAdminCanCreateAProduct() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"New Widget","skuCode":"SKU-PC-NEW","type":"Hardware",
                                 "unitPrice":12.5,"description":"Brand new"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("New Widget"))
                // Omitted isActive means active. Java's primitive default is false, so this asserts
                // the service sets it explicitly rather than letting the field default.
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void aProductCanBeCreatedRetired() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Born Retired","skuCode":"SKU-PC-BORN","type":"Hardware",
                                 "unitPrice":1.0,"description":"Never sold","isActive":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    void aSalesRepCannotCreateAProduct() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor("rep@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nope","skuCode":"SKU-PC-NOPE","type":"Hardware",
                                 "unitPrice":1.0,"description":"No"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDuplicateSkuIsRejected() throws Exception {
        // SKU_Code is UNIQUE in V1. 409 comes from the handler's DataIntegrityViolationException
        // mapping, which is worth pinning because nothing else in this group relies on it.
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Clash","skuCode":"SKU-PC-LIVE","type":"Hardware",
                                 "unitPrice":1.0,"description":"Same SKU"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void aNegativePriceIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Free Money","skuCode":"SKU-PC-NEG","type":"Hardware",
                                 "unitPrice":-1.0,"description":"Negative"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMissingRequiredFieldIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuCode":"SKU-PC-NONAME","type":"Hardware","unitPrice":1.0,
                                 "description":"No name"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAdminCanUpdateAProduct() throws Exception {
        mockMvc.perform(put("/api/v1/products/pc-live")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPrice\":150.0,\"description\":\"Now pricier\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPrice").value(150.0))
                .andExpect(jsonPath("$.description").value("Now pricier"));
    }

    @Test
    void anOmittedPriceIsNotZeroedOnUpdate() throws Exception {
        // The reason unitPrice is a Double wrapper on the update request and a primitive on the
        // create request. A primitive here would silently set every untouched price to 0.0.
        mockMvc.perform(put("/api/v1/products/pc-live")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed Only\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Only"))
                .andExpect(jsonPath("$.unitPrice").value(100.0));
    }

    @Test
    void aSalesRepCannotUpdateAProduct() throws Exception {
        mockMvc.perform(put("/api/v1/products/pc-live")
                        .header("Authorization", "Bearer " + tokenFor("rep@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitPrice\":1.0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProductCanBeReactivated() throws Exception {
        mockMvc.perform(put("/api/v1/products/pc-dead")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true));
    }

    // --- soft delete -------------------------------------------------------------------------

    @Test
    void deleteIsSoftAndTheRowSurvives() throws Exception {
        mockMvc.perform(delete("/api/v1/products/pc-live")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example")))
                .andExpect(status().isNoContent());

        assertStoredActive("pc-live", false);

        // Still resolvable by id, which is the entire point of a soft delete: line_items references
        // it with ON DELETE RESTRICT and a historical deal must still render.
        mockMvc.perform(get("/api/v1/products/pc-live")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    void aSoftDeletedProductLeavesTheActiveCatalogue() throws Exception {
        mockMvc.perform(delete("/api/v1/products/pc-live")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/products?activeOnly=true")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productId == 'pc-live')]", hasSize(0)));
    }

    @Test
    void aSalesRepCannotDeleteAProduct() throws Exception {
        mockMvc.perform(delete("/api/v1/products/pc-live")
                        .header("Authorization", "Bearer " + tokenFor("rep@prodco.example")))
                .andExpect(status().isForbidden());

        assertStoredActive("pc-live", true);
    }

    @Test
    void theSoftDeleteIsAuditedAsAnUpdate() throws Exception {
        // Logged as UPDATE, not DELETE: logDelete records the before-state as "the only surviving
        // copy of the row", which would be false here.
        mockMvc.perform(delete("/api/v1/products/pc-live")
                        .header("Authorization", "Bearer " + tokenFor("admin@prodco.example")))
                .andExpect(status().isNoContent());

        assertAuditRowCount("UPDATE", 1);
        assertAuditRowCount("DELETE", 0);
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

    private static void assertStoredActive(String productId, boolean expected) throws Exception {
        try (Connection connection = superuser(); Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT \"Is_Active\" FROM products WHERE \"Product_ID\" = '" + productId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("Is_Active")).isEqualTo(expected);
            }
        }
    }

    private static void assertAuditRowCount(String actionType, int expected) throws Exception {
        try (Connection connection = superuser(); Statement stmt = connection.createStatement()) {
            stmt.execute("SET app.bypass_rls = 'true'");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT count(*) FROM events_log WHERE \"Action_Type\" = '" + actionType
                            + "' AND \"Object_Type\" = 'Product'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(expected);
            }
        }
    }

    private static Connection superuser() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
