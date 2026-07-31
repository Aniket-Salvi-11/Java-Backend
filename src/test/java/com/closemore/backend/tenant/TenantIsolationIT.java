package com.closemore.backend.tenant;

import com.closemore.backend.context.RequestUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The release gate the migration plan describes. Runs against the REAL schema (V1-V9), applied by
 * Flyway against a throwaway Testcontainers Postgres. Run with {@code mvn verify} (Failsafe), not
 * {@code mvn test}. Requires Docker.
 *
 * <p>All container and datasource wiring now lives in {@link AbstractRlsIT}. In particular the
 * application connects as the non-superuser {@code closemore_app} role created during container
 * initdb, so ENABLE/FORCE ROW LEVEL SECURITY actually applies to these queries; as the container
 * superuser every assertion below would fail with "expected 1 but was 2".
 *
 * <p>{@link RlsWiringPreconditionsIT} asserts that precondition directly, so if this class starts
 * failing you can tell a broken policy from a broken connection at a glance.
 */
class TenantIsolationIT extends AbstractRlsIT {

    @Autowired
    TenantIsolationProbeService probeService;

    @Autowired
    ClassLevelTransactionalProbeService classLevelProbeService;

    @Test
    void concurrentTenantsOnASharedPoolNeverSeeEachOthersRows() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Integer> tenantA = CompletableFuture.supplyAsync(
                    () -> asTenant("user-a", "Sales_Rep", "Acme", this::repeatedCount), pool);
            CompletableFuture<Integer> tenantB = CompletableFuture.supplyAsync(
                    () -> asTenant("user-b", "Sales_Rep", "Globex", this::repeatedCount), pool);

            assertThat(tenantA.get()).isEqualTo(1);
            assertThat(tenantB.get()).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aTenantCannotReachAnotherTenantsRowEvenByPrimaryKey() {
        int ownRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.countContactById("contact-a"));
        int foreignRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.countContactById("contact-b"));

        assertThat(ownRow).isEqualTo(1);
        assertThat(foreignRow).isZero();
    }

    @Test
    void classLevelTransactionalServicesAlsoGetTheSessionVariables() {
        int visible = asTenant("user-a", "Sales_Rep", "Acme",
                () -> classLevelProbeService.countVisibleContacts());

        assertThat(visible).isEqualTo(1);
    }

    @Test
    void aRequestWithNoUserContextSeesNothing() {
        contextHolder.clear();

        assertThat(probeService.countVisibleContacts()).isZero();
    }

    @Test
    void sessionVariablesDoNotSurviveOntoTheNextRequestOnTheSameConnection() {
        String duringRequest = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.currentTenantSessionVariable());
        contextHolder.clear();
        String afterRequest = probeService.currentTenantSessionVariable();

        assertThat(duringRequest).isEqualTo("Acme");
        // set_config(..., true) is transaction-local: on COMMIT the GUC reverts to its session
        // default, which for a never-explicitly-set custom GUC reads back as the empty string.
        assertThat(afterRequest).isNullOrEmpty();
    }

    @Test
    void aspectRefusesToRunOutsideARealTransaction() {
        contextHolder.set(new RequestUserContext("user-a", "Sales_Rep", "Acme"));
        try {
            assertThatThrownBy(() -> probeService.countWithNoTransaction())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no active transaction");
        } finally {
            contextHolder.clear();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Regression guards for V10 (events_log RLS) and V11 (users policy tightening).
    // Each of these FAILED before those migrations: the first two returned every row in the
    // database regardless of tenant.
    // ----------------------------------------------------------------------------------------

    @Test
    void theUserDirectoryIsTenantScoped() {
        int asAcme = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.countVisibleUsers());
        int asGlobex = asTenant("user-b", "Sales_Rep", "Globex",
                () -> probeService.countVisibleUsers());

        assertThat(asAcme).isEqualTo(1);
        assertThat(asGlobex).isEqualTo(1);
    }

    @Test
    void aRequestWithNoUserContextCannotEnumerateTheUserDirectory() {
        contextHolder.clear();

        // V4's policy ended with "OR current_setting(...) IS NULL OR ... = ''", so this returned
        // every user in every tenant. V11 removes that clause.
        assertThat(probeService.countVisibleUsers()).isZero();
    }

    @Test
    void theAuditTrailIsTenantScoped() {
        List<String> asAcme = asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.visibleAuditActorIds());
        List<String> asGlobex = asTenant("user-b", "Sales_Rep", "Globex",
                () -> probeService.visibleAuditActorIds());

        assertThat(asAcme).containsOnly("user-a");
        assertThat(asGlobex).containsOnly("user-b");
    }

    @Test
    void aRequestWithNoUserContextSeesNoAuditTrail() {
        contextHolder.clear();

        assertThat(probeService.visibleAuditActorIds()).isEmpty();
    }

    @Test
    void aTenantCannotForgeAnAuditEntryAgainstAnotherTenantsUser() {
        // NOTE ON THE ASSERTION STYLE, because the obvious version of this test is wrong:
        //
        // An RLS WITH CHECK violation is SQLSTATE 42501. Spring's SQLStateSQLExceptionTranslator
        // reads the class code "42", finds it in BAD_SQL_GRAMMAR_CODES, and returns
        // `new BadSqlGrammarException(task, sql, ex)` - whose constructor is the ONE branch in
        // that translator that does not call buildMessage(), so it drops ex.getMessage()
        // entirely. Every sibling branch keeps it. The Postgres text therefore exists only on
        // the cause, and hasMessageContaining() - which inspects only the top-level message -
        // cannot see it.
        //
        // hasStackTraceContaining() renders the full trace including "Caused by:" lines, so it
        // matches. Asserting the specific Postgres wording (rather than just the exception type)
        // matters here: BadSqlGrammarException is also what genuinely malformed SQL produces, so
        // the type alone would let a broken INSERT masquerade as a passing security test.
        assertThatThrownBy(() -> asTenant("user-a", "Sales_Rep", "Acme",
                () -> {
                    probeService.writeAuditEvent("user-b", "contact-b");
                    return null;
                }))
                .as("WITH CHECK on events_log_rls_policy must reject this")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("new row violates row-level security policy");
    }

    @Test
    void aTenantCanStillWriteItsOwnAuditEntries() {
        asTenant("user-a", "Sales_Rep", "Acme", () -> {
            probeService.writeAuditEvent("user-a", "contact-a");
            return null;
        });

        assertThat(asTenant("user-a", "Sales_Rep", "Acme",
                () -> probeService.visibleAuditActorIds()))
                .containsOnly("user-a");
    }

    @Test
    void theLoginDoorResolvesAUserWithoutOpeningTheDirectory() {
        contextHolder.clear();

        // The narrow SECURITY DEFINER lookup V11 adds so authentication still works pre-tenant.
        assertThat(probeService.loginLookupOrganization("A-Owner@Example.com"))
                .as("case-insensitive, mirroring the original LOWER(\"Email\") login query")
                .isEqualTo("Acme");
        assertThat(probeService.loginLookupOrganization("nobody@example.com")).isNull();

        // ...and the function's bypass does not leak: the directory is still shut afterwards.
        assertThat(probeService.countVisibleUsers()).isZero();
    }

    private int repeatedCount() {
        int last = -1;
        for (int i = 0; i < 5; i++) {
            last = probeService.countVisibleContacts();
        }
        return last;
    }
}