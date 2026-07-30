package com.closemore.backend.tenant;

import com.closemore.backend.domain.ContactEntity;
import com.closemore.backend.repository.ContactRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only. Runs Spring Data / Hibernate reads through the same
 * {@code @Transactional -> TenantContextAspect -> connection} path a production service will use.
 *
 * <p>Previously this lived as an inner class registered via a {@code @TestConfiguration} on
 * {@code RepositoryTenantIsolationIT}. That extra {@code @Import} made the repository IT's context
 * cache key differ from the other ITs', so Spring built and cached a second application context.
 * Promoting it to a plain component-scanned {@code @Service} in the test sources lets every IT in
 * the suite share one context -- one container, one Flyway run, one Hibernate bootstrap.
 */
@Service
@RequiredArgsConstructor
public class ContactReadService {

    private final ContactRepository contacts;

    @Transactional
    public List<ContactEntity> listAll() {
        return contacts.findAll();
    }

    @Transactional
    public Optional<ContactEntity> byId(String id) {
        return contacts.findById(id);
    }
}
