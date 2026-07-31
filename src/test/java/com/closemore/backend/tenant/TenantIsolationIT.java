package com.closemore.backend.tenant;

import com.closemore.backend.context.RequestUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    private int repeatedCount() {
        int last = -1;
        for (int i = 0; i < 5; i++) {
            last = probeService.countVisibleContacts();
        }
        return last;
    }
}