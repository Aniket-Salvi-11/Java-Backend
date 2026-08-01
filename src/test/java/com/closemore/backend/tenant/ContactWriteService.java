package com.closemore.backend.tenant;

import com.closemore.backend.domain.ContactEntity;
import com.closemore.backend.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only, and the only WRITE path in the suite that goes through Hibernate for a normal entity
 * (EventLogReadService covers the generated-key case).
 *
 * <p>saveAndFlush rather than save on purpose: Hibernate defers SQL to flush, and these tests care
 * about what the database does during the statement - the trigger firing, the generated value being
 * read back. Without the flush the INSERT would not have happened yet when the assertion runs.
 */
@Service
@RequiredArgsConstructor
public class ContactWriteService {

    private final ContactRepository contacts;

    @Transactional
    public ContactEntity create(ContactEntity contact) {
        return contacts.saveAndFlush(contact);
    }

    @Transactional
    public ContactEntity rename(String contactId, String newFirstName) {
        ContactEntity existing = contacts.findById(contactId).orElseThrow();
        existing.setFirstName(newFirstName);
        return contacts.saveAndFlush(existing);
    }
}
