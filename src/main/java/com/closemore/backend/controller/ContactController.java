package com.closemore.backend.controller;

import com.closemore.backend.dto.ContactCreateRequest;
import com.closemore.backend.dto.ContactResponse;
import com.closemore.backend.dto.ContactUpdateRequest;
import com.closemore.backend.service.ContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contacts. Thin by design, like AuthController - binding and nothing else.
 *
 * <p><b>{@code /api/v1} starts here.</b> The auth endpoints stay on {@code /api/auth/*} unversioned:
 * they are already in {@code JwtAuthenticationFilter.PUBLIC_PATH_PREFIXES} and already in whatever
 * the JS frontend ships today, so moving them is a coordinated release. Every resource group ported
 * from here on is versioned, because the alternative is discovering the need for {@code v2} while a
 * mobile build from six months ago is still calling {@code v1}.
 *
 * <p>Nothing here is on the public path list, so a request without a valid token never reaches these
 * methods - the filter answers 401 first.
 *
 * <p><b>The list endpoint returns two different shapes, and that is the decision, not an accident.</b>
 * Called plainly it returns a bare JSON array, exactly as the Next.js backend does. Called with
 * {@code ?page=} or {@code ?size=} it returns a PageResponse envelope. Clients therefore adopt
 * pagination screen by screen, with no coordinated release and nothing breaking on the day this
 * ships. See PageResponse for the full reasoning.
 *
 * <p><b>The default sort is two keys, not one.</b> Sorting by {@code lastName} alone leaves rows with
 * equal last names in whatever order Postgres returns them, which is free to differ between the query
 * for page 1 and the query for page 2 - so a contact can appear twice, or never. {@code contactId}
 * as a tiebreaker makes the ordering total, which is what pagination actually requires.
 */
@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    /**
     * GET /api/v1/contacts
     *
     * <p>The return type is Object because the response shape is genuinely one of two types. The
     * alternative - two endpoints on different paths - would put the choice in the URL, where a
     * client cannot change its mind per screen without changing the URL it calls.
     *
     * <p>{@code page} and {@code size} are declared here purely to detect their presence; the values
     * are consumed by the resolved {@code Pageable}. Either one opts the caller in: a client asking
     * for {@code ?size=50} plainly wants pages, even without naming one.
     */
    @GetMapping
    public Object list(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @PageableDefault(size = 25,
                    sort = {"lastName", "contactId"},
                    direction = Sort.Direction.ASC) Pageable pageable) {

        boolean paginationRequested = page != null || size != null;

        return paginationRequested
                ? contactService.listPage(pageable)
                : contactService.listAll(pageable.getSort());
    }

    /**
     * GET /api/v1/contacts/{contactId}
     *
     * <p>Not present in the Next.js backend, and added deliberately. Pagination makes it necessary:
     * once a list arrives 25 rows at a time, a deep link or a push notification pointing at one
     * contact would otherwise have to walk pages until it found the row.
     */
    @GetMapping("/{contactId}")
    public ContactResponse get(@PathVariable String contactId) {
        return contactService.get(contactId);
    }

    /** POST /api/v1/contacts */
    @PostMapping
    public ResponseEntity<ContactResponse> create(@Valid @RequestBody ContactCreateRequest request) {
        ContactResponse created = contactService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** PUT /api/v1/contacts/{contactId} */
    @PutMapping("/{contactId}")
    public ContactResponse update(@PathVariable String contactId,
                                  @Valid @RequestBody ContactUpdateRequest request) {
        return contactService.update(contactId, request);
    }

    /**
     * DELETE /api/v1/contacts/{contactId}
     *
     * <p>204 with no body. A deleted resource has no representation to return, and sending the row
     * back invites a client to treat it as still present.
     */
    @DeleteMapping("/{contactId}")
    public ResponseEntity<Void> delete(@PathVariable String contactId) {
        contactService.delete(contactId);
        return ResponseEntity.noContent().build();
    }
}
