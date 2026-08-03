package com.closemore.backend.service;

import com.closemore.backend.domain.ContactEntity;
import com.closemore.backend.dto.ContactCreateRequest;
import com.closemore.backend.dto.ContactResponse;
import com.closemore.backend.dto.ContactUpdateRequest;
import com.closemore.backend.dto.PageResponse;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.rbac.Role;
import com.closemore.backend.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The contacts resource group. The first service in the project that reaches the database through
 * JPA on behalf of an HTTP request, which makes it the template the other eleven groups copy - so
 * the shape here matters more than the five methods do.
 *
 * <p><b>Class-level {@code @Transactional} is load-bearing, not decoration.</b> TenantContextAspect
 * pointcuts on {@code @annotation(Transactional) || @within(Transactional)} and only then issues the
 * three {@code set_config} calls. A method here that is not transactional gets no session variables,
 * every RLS policy evaluates against unset settings, and the endpoint returns an empty list - a
 * plausible-looking answer with no error anywhere.
 *
 * <p>{@code readOnly = true} belongs on the read methods and is deliberately absent for now: it
 * changes how Spring prepares the connection, and nothing yet proves {@code set_config} still
 * applies in a read-only transaction. That is its own batch, with a test.
 *
 * <p><b>The RbacService calls are redundant with RLS, on purpose.</b> The policy already restricts a
 * Sales_Rep to rows they own, so the {@code canViewAll} branch cannot change the result set today.
 * It is defence in depth, mirroring the JS original: if a bad migration ever weakens
 * {@code contacts_rls_policy}, this branch still holds; if this branch is ever refactored away, the
 * policy does. Both would have to fail together.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ContactService {

    private static final String OBJECT_TYPE = "Contact";

    /**
     * Sort keys a caller may name, as JPA property names.
     *
     * <p>An allowlist rather than passing {@code Pageable} straight through, for two reasons. An
     * unknown property makes Spring Data throw during query derivation, which surfaces as a 500 for
     * what is really a typo. And the two omissions are the point: {@code avatarDataUrl} and
     * {@code orgAvatarDataUrl} hold inline base64 images, so sorting by either asks Postgres to
     * collate multi-megabyte strings across the whole table - a trivially available way to make the
     * database work very hard.
     */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "contactId", "firstName", "lastName", "email", "phonePrimary",
            "organizationName", "contactType", "source", "createdDate",
            "ownerId", "createdAt", "updatedAt");

    private final ContactRepository contactRepository;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    // --- reads ----------------------------------------------------------------------------

    /**
     * GET /api/v1/contacts with no {@code ?page=} - every visible contact, as a bare array.
     *
     * <p>Unbounded, matching the Next.js backend. That is a real cost with inline avatars and is
     * recorded in the migration plan as such, but capping it here would silently truncate a list
     * that clients currently receive whole, which is the one failure mode worse than a slow response.
     */
    public List<ContactResponse> listAll(Sort sort) {
        AuthenticatedUser user = currentUserService.require();
        requireSortablePropertiesOnly(sort);

        return visibleContacts(user, Pageable.unpaged(sort))
                .getContent().stream()
                .map(DtoMapper::toContactResponse)
                .toList();
    }

    /** GET /api/v1/contacts?page=... - the opt-in paginated form. */
    public PageResponse<ContactResponse> listPage(Pageable pageable) {
        AuthenticatedUser user = currentUserService.require();
        requireSortablePropertiesOnly(pageable.getSort());

        return PageResponse.of(visibleContacts(user, pageable), DtoMapper::toContactResponse);
    }

    /** GET /api/v1/contacts/{contactId} */
    public ContactResponse get(String contactId) {
        AuthenticatedUser user = currentUserService.require();
        ContactEntity contact = loadVisible(contactId);
        rbacService.requireOwnerOrAdmin(user, contact.getOwnerId());
        return DtoMapper.toContactResponse(contact);
    }

    // --- writes ---------------------------------------------------------------------------

    /** POST /api/v1/contacts */
    public ContactResponse create(ContactCreateRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        ContactEntity contact = new ContactEntity();
        contact.setContactId(UUID.randomUUID().toString());
        contact.setOwnerId(resolveOwner(user, request.ownerId()));
        contact.setCreatedDate(request.createdDate() == null || request.createdDate().isBlank()
                ? LocalDate.now().toString()
                : request.createdDate());
        applyEditableFields(contact, request.firstName(), request.lastName(), request.email(),
                request.phonePrimary(), request.organizationName(), request.contactType(),
                request.source(), request.avatarDataUrl(), request.orgAvatarDataUrl());

        // saveAndFlush, not save: the INSERT must reach the database inside this method so that an
        // RLS refusal surfaces here rather than at commit, where it would escape this transaction's
        // error handling and arrive as an opaque 500.
        ContactEntity saved = contactRepository.saveAndFlush(contact);
        ContactResponse response = DtoMapper.toContactResponse(saved);

        auditService.logCreate(user, OBJECT_TYPE, saved.getContactId(), displayName(saved), response);
        return response;
    }

    /** PUT /api/v1/contacts/{contactId} - a full replace, not a patch. */
    public ContactResponse update(String contactId, ContactUpdateRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        ContactEntity contact = loadVisible(contactId);
        rbacService.requireOwnerOrAdmin(user, contact.getOwnerId());

        // Snapshot BEFORE mutating. The entity is managed, so every setter below is already
        // reflected in it - capture the prior state first or the audit entry records the new values
        // twice and the diff is lost permanently.
        ContactResponse before = DtoMapper.toContactResponse(contact);

        applyEditableFields(contact, request.firstName(), request.lastName(), request.email(),
                request.phonePrimary(), request.organizationName(), request.contactType(),
                request.source(), request.avatarDataUrl(), request.orgAvatarDataUrl());

        ContactEntity saved = contactRepository.saveAndFlush(contact);
        ContactResponse after = DtoMapper.toContactResponse(saved);

        auditService.logUpdate(user, OBJECT_TYPE, contactId, displayName(saved), before, after);
        return after;
    }

    /** DELETE /api/v1/contacts/{contactId} */
    public void delete(String contactId) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        ContactEntity contact = loadVisible(contactId);
        rbacService.requireOwnerOrAdmin(user, contact.getOwnerId());

        // The audit entry is the only surviving copy of the row once this commits, so it is built
        // before the delete and written after - if the delete is refused, no entry claims it happened.
        ContactResponse before = DtoMapper.toContactResponse(contact);
        String name = displayName(contact);

        contactRepository.delete(contact);
        contactRepository.flush();

        auditService.logDelete(user, OBJECT_TYPE, contactId, name, before);
    }

    // --- helpers --------------------------------------------------------------------------

    private Page<ContactEntity> visibleContacts(AuthenticatedUser user, Pageable pageable) {
        return rbacService.canViewAll(user)
                ? contactRepository.findAll(pageable)
                : contactRepository.findByOwnerId(user.userId(), pageable);
    }

    /**
     * Loads a contact or throws 404.
     *
     * <p>RLS has already decided this. A row belonging to another tenant, or to another rep, is not
     * returned at all - see {@link ResourceNotFoundException} for why 404 rather than 403 is the
     * correct answer.
     */
    private ContactEntity loadVisible(String contactId) {
        return contactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("Contact not found"));
    }

    /**
     * Ownership on create. Defaults to the caller; only an Admin may assign a contact to someone
     * else.
     *
     * <p>A Sales_Rep naming another user is quietly given their own id rather than refused. RLS
     * would reject the insert anyway, so the outcome is the same - but a 403 here would confirm that
     * the id supplied belongs to a real user, which is an enumeration oracle for free.
     */
    private String resolveOwner(AuthenticatedUser user, String requestedOwnerId) {
        boolean canAssign = user.role() == Role.ADMIN;
        return canAssign && requestedOwnerId != null && !requestedOwnerId.isBlank()
                ? requestedOwnerId
                : user.userId();
    }

    private void applyEditableFields(ContactEntity contact, String firstName, String lastName,
                                     String email, String phonePrimary, String organizationName,
                                     String contactType, String source,
                                     String avatarDataUrl, String orgAvatarDataUrl) {
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(email);
        contact.setPhonePrimary(phonePrimary);
        contact.setOrganizationName(organizationName);
        contact.setContactType(contactType);
        contact.setSource(source);
        contact.setAvatarDataUrl(avatarDataUrl);
        contact.setOrgAvatarDataUrl(orgAvatarDataUrl);
    }

    private String displayName(ContactEntity contact) {
        return (contact.getFirstName() + " " + contact.getLastName()).trim();
    }

    private void requireSortablePropertiesOnly(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty() + "'");
            }
        }
    }
}
