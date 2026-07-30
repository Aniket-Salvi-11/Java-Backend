package com.closemore.backend.tenant;

import com.closemore.backend.domain.ContactEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE Phase 1 de-risking test: proves RLS holds when HIBERNATE runs the query through a Spring Data
 * repository, not just through JdbcTemplate. Hibernate builds its own SQL, manages its own
 * first-level cache and may reuse a connection across the flush boundary, so passing
 * {@link TenantIsolationIT} does not by itself prove this path is safe.
 *
 * <p>Container and datasource wiring live in {@link AbstractRlsIT}; the application queries as the
 * restricted {@code closemore_app} role so the policies in V4 actually apply.
 */
class RepositoryTenantIsolationIT extends AbstractRlsIT {

    @Autowired
    ContactReadService readService;

    @Test
    void hibernateFindAllReturnsOnlyTheCurrentTenantsRows() {
        List<ContactEntity> asAcme = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.listAll());
        List<ContactEntity> asGlobex = asTenant("user-b", "Sales_Rep", "Globex",
                () -> readService.listAll());

        assertThat(asAcme).extracting(ContactEntity::getContactId).containsExactly("contact-a");
        assertThat(asGlobex).extracting(ContactEntity::getContactId).containsExactly("contact-b");
    }

    @Test
    void hibernateFindByIdCannotLoadAnotherTenantsRow() {
        Optional<ContactEntity> ownRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.byId("contact-a"));
        Optional<ContactEntity> foreignRow = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.byId("contact-b"));

        assertThat(ownRow).isPresent();
        assertThat(foreignRow).isEmpty();
    }

    @Test
    void entitiesMatchTheRealSchema_validateWouldHaveFailedOtherwise() {
        ContactEntity loaded = asTenant("user-a", "Sales_Rep", "Acme",
                () -> readService.byId("contact-a")).orElseThrow();

        assertThat(loaded.getOrganizationName()).isEqualTo("Acme Client Co");
        assertThat(loaded.getOwnerId()).isEqualTo("user-a");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }
}