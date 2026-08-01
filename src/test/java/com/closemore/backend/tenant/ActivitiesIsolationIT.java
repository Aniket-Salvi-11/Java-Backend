package com.closemore.backend.tenant;

import com.closemore.backend.domain.ActivityAttachmentEntity;
import com.closemore.backend.domain.ActivityEntity;
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
 * The polymorphic-parent case, and the deepest policy in the schema.
 *
 * <p>activities_rls_policy branches on Parent_Object_Type and offers FOUR routes to read access,
 * all behind the requirement that the LOGGER shares your organisation:
 * <ol>
 *   <li>you are Admin or Executive;</li>
 *   <li>you logged it yourself;</li>
 *   <li>the parent is a Deal you own;</li>
 *   <li>the parent is a Contact you own.</li>
 * </ol>
 *
 * <p>activity_attachments then re-evaluates that entire branch one level further out, joining
 * through activities to users and back down into deals or contacts. Three levels with a
 * discriminator in the middle - if any policy in this schema is quietly wrong, it is that one.
 *
 * <p>Fixture, all in the Initech organisation so Acme and Globex counts elsewhere are untouched:
 * <ul>
 *   <li>{@code act-logger} logged both activities</li>
 *   <li>{@code act-dealowner} owns both the parent deal and the parent contact</li>
 *   <li>{@code act-stranger} is a TEAM MEMBER on the parent deal and nothing else</li>
 * </ul>
 */
class ActivitiesIsolationIT extends AbstractRlsIT {

    @Autowired
    ActivitiesReadService readService;

    @BeforeEach
    void seedActivities() throws Exception {
        PostgreSQLContainer<?> postgres = RlsPostgres.instance();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.bypass_rls = 'true'");
                stmt.execute("""
                        INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status","Organization_Name")
                        VALUES
                          ('act-logger','Act','Logger','act-logger@example.com','Sales_Rep','Active','Initech'),
                          ('act-dealowner','Act','Owner','act-owner@example.com','Sales_Rep','Active','Initech'),
                          ('act-stranger','Act','Stranger','act-stranger@example.com','Sales_Rep','Active','Initech'),
                          ('act-admin','Act','Admin','act-admin@example.com','Admin','Active','Initech')
                        ON CONFLICT ("User_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO contacts ("Contact_ID","First_Name","Last_Name","Email","Phone_Primary",
                          "Organization_Name","Contact_Type","Source","Created_Date","Owner_ID")
                        VALUES ('contact-act','Act','Client','act-client@example.com','555-4001',
                                'Act Client Co','Lead','Test','2026-01-01','act-dealowner')
                        ON CONFLICT ("Contact_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO pipelines ("Pipeline_ID","Pipeline_Name","Stages_JSON")
                        VALUES ('pipe-act','Activities Test Pipeline','[]'::jsonb)
                        ON CONFLICT ("Pipeline_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO deals ("Deal_ID","Deal_Name","Associated_Contact_ID","Pipeline_ID",
                          "Current_Stage","Deal_Value","Expected_Close_Date","Probability_Percentage",
                          "Owner_ID","Status","ARR","TCV","TLV","Commission","Partner_Commission",
                          "Distributor_Commission")
                        VALUES ('deal-act','Act Deal','contact-act','pipe-act','Proposal',1000,
                                '2026-06-30',50,'act-dealowner','Open',0,0,0,0,0,0)
                        ON CONFLICT ("Deal_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO deal_team_members ("Deal_ID","User_ID")
                        VALUES ('deal-act','act-stranger')
                        ON CONFLICT ("Deal_ID","User_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO activities ("Log_ID","Parent_Object_Type","Parent_Object_ID",
                          "Activity_Type","Summary","Detailed_Description","Log_Date","Logged_By_User_ID")
                        VALUES
                          ('act-on-deal','Deal','deal-act','Call','Called client','Long chat',
                           '2026-02-01','act-logger'),
                          ('act-on-contact','Contact','contact-act','Email','Sent proposal','Body',
                           '2026-02-02','act-logger')
                        ON CONFLICT ("Log_ID") DO NOTHING
                        """);
                stmt.execute("""
                        INSERT INTO activity_attachments ("Attachment_ID","Log_ID","File_Name",
                          "Mime_Type","File_Size","Storage_Path","Uploaded_By_User_ID","Uploaded_At")
                        VALUES ('att-deal','act-on-deal','notes.pdf','application/pdf',2048,
                                '/storage/notes.pdf','act-logger','2026-02-01T10:00:00Z')
                        ON CONFLICT ("Attachment_ID") DO NOTHING
                        """);
            }
            connection.commit();
        }
    }

    @Test
    void theLoggerSeesTheirOwnActivitiesAndAttachments() {
        List<ActivityEntity> visible = asTenant("act-logger", "Sales_Rep", "Initech",
                () -> readService.allActivities());
        List<ActivityAttachmentEntity> attachments = asTenant("act-logger", "Sales_Rep", "Initech",
                () -> readService.allAttachments());

        assertThat(visible).extracting(ActivityEntity::getLogId)
                .contains("act-on-deal", "act-on-contact");
        assertThat(attachments).extracting(ActivityAttachmentEntity::getAttachmentId)
                .contains("att-deal");
    }

    /** Route 3 and route 4: owning the parent grants access even though someone else logged it. */
    @Test
    void owningTheParentDealOrContactGrantsAccessToActivitiesLoggedByOthers() {
        List<ActivityEntity> visible = asTenant("act-dealowner", "Sales_Rep", "Initech",
                () -> readService.allActivities());

        assertThat(visible).extracting(ActivityEntity::getLogId)
                .as("act-dealowner logged neither, but owns both parents")
                .contains("act-on-deal", "act-on-contact");
    }

    /**
     * THE GAP. V9 added deal_team_members as a route into deals, line_items and deal_contacts, but
     * never touched activities. So a team member can open the deal and still see nothing logged
     * against it. This looks like an oversight in the original rather than a decision - pinned here
     * so that if someone later extends the activities policy, this test fails loudly and the
     * change is deliberate rather than incidental.
     */
    @Test
    void aDealTeamMemberCanOpenTheDealButSeesNoneOfItsActivities() {
        List<ActivityEntity> activities = asTenant("act-stranger", "Sales_Rep", "Initech",
                () -> readService.activitiesForParent("Deal", "deal-act"));
        List<ActivityAttachmentEntity> attachments = asTenant("act-stranger", "Sales_Rep", "Initech",
                () -> readService.attachmentsForLog("act-on-deal"));

        assertThat(activities).isEmpty();
        assertThat(attachments).isEmpty();
    }

    @Test
    void anAdminInTheSameOrganisationSeesEverything() {
        List<ActivityEntity> visible = asTenant("act-admin", "Admin", "Initech",
                () -> readService.allActivities());

        assertThat(visible).extracting(ActivityEntity::getLogId)
                .contains("act-on-deal", "act-on-contact");
    }

    @Test
    void anAdminInAnotherOrganisationSeesNothing() {
        List<ActivityEntity> activities = asTenant("user-a", "Admin", "Acme",
                () -> readService.allActivities());
        List<ActivityAttachmentEntity> attachments = asTenant("user-a", "Admin", "Acme",
                () -> readService.allAttachments());

        assertThat(activities).extracting(ActivityEntity::getLogId)
                .doesNotContain("act-on-deal", "act-on-contact");
        assertThat(attachments).extracting(ActivityAttachmentEntity::getAttachmentId)
                .doesNotContain("att-deal");
    }

    @Test
    void theDiscriminatorQueryDistinguishesDealParentsFromContactParents() {
        List<ActivityEntity> onDeal = asTenant("act-logger", "Sales_Rep", "Initech",
                () -> readService.activitiesForParent("Deal", "deal-act"));
        List<ActivityEntity> onContact = asTenant("act-logger", "Sales_Rep", "Initech",
                () -> readService.activitiesForParent("Contact", "contact-act"));

        assertThat(onDeal).extracting(ActivityEntity::getLogId).containsExactly("act-on-deal");
        assertThat(onContact).extracting(ActivityEntity::getLogId).containsExactly("act-on-contact");
    }

    @Test
    void allColumnsMapIncludingTheTextUploadedAtAndNullableFields() {
        ActivityEntity activity = asTenant("act-logger", "Sales_Rep", "Initech",
                () -> readService.activityById("act-on-deal")).orElseThrow();
        ActivityAttachmentEntity attachment = asTenant("act-logger", "Sales_Rep", "Initech",
                () -> readService.attachmentsForLog("act-on-deal")).get(0);

        assertThat(activity.getParentObjectType()).isEqualTo("Deal");
        assertThat(activity.getParentObjectId()).isEqualTo("deal-act");
        assertThat(activity.getActivityType()).isEqualTo("Call");
        assertThat(activity.getSummary()).isEqualTo("Called client");
        assertThat(activity.getDetailedDescription()).isEqualTo("Long chat");
        assertThat(activity.getAttachmentUrl()).isNull();
        assertThat(activity.getFollowUpDate()).isNull();
        assertThat(activity.getLogDate()).isEqualTo("2026-02-01");
        assertThat(activity.getCreatedAt()).isNotNull();
        assertThat(activity.getUpdatedAt()).isNotNull();

        assertThat(attachment.getFileName()).isEqualTo("notes.pdf");
        assertThat(attachment.getMimeType()).isEqualTo("application/pdf");
        assertThat(attachment.getFileSize()).isEqualTo(2048);
        assertThat(attachment.getStoragePath()).isEqualTo("/storage/notes.pdf");
        // TEXT in the schema, not TIMESTAMPTZ - mapping it as a temporal type fails validation.
        assertThat(attachment.getUploadedAt()).isEqualTo("2026-02-01T10:00:00Z");
    }
}
