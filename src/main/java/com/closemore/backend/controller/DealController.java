package com.closemore.backend.controller;

import com.closemore.backend.dto.DealContactRequest;
import com.closemore.backend.dto.DealContactResponse;
import com.closemore.backend.dto.DealCreateRequest;
import com.closemore.backend.dto.DealDetailResponse;
import com.closemore.backend.dto.DealLostRequest;
import com.closemore.backend.dto.DealResponse;
import com.closemore.backend.dto.DealStoryResponse;
import com.closemore.backend.dto.DealUpdateRequest;
import com.closemore.backend.dto.LineItemRequest;
import com.closemore.backend.dto.StageChangeRequest;
import com.closemore.backend.service.DealService;
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
 * Deals - thirteen endpoints, the largest resource group in the port.
 *
 * <p>Thin by design, like ContactController: binding and nothing else. Nothing here is on
 * {@code JwtAuthenticationFilter.PUBLIC_PATH_PREFIXES}, so a request without a valid token never
 * reaches these methods.
 *
 * <p><b>The sub-resources are nested under the deal, and the deal id in the path is checked.</b>
 * {@code /deals/{dealId}/line-items/{lineItemId}} could have been {@code /line-items/{id}}, but then
 * a caller could edit any line item it could name, and the line_items RLS policy would allow it -
 * the policy authorises the row through its own deal and has no way to know which deal the caller
 * claimed. The nesting is what makes that check possible; the service performs it.
 *
 * <p><b>The list endpoint returns two different shapes.</b> Called plainly it returns a bare JSON
 * array, exactly as the Next.js backend does. Called with {@code ?page=} or {@code ?size=} it
 * returns a PageResponse envelope. See PageResponse for the full reasoning.
 */
@RestController
@RequestMapping("/api/v1/deals")
@RequiredArgsConstructor
public class DealController {

    private final DealService dealService;

    /**
     * GET /api/v1/deals - filter by status, stage and owner; sort; optionally paginate.
     *
     * <p>The return type is Object because the response shape is genuinely one of two types. Either
     * {@code page} or {@code size} opts the caller in: a client asking for {@code ?size=50} plainly
     * wants pages even without naming one.
     */
    @GetMapping
    public Object list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "stage", required = false) String stage,
            @RequestParam(name = "ownerId", required = false) String ownerId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @PageableDefault(size = 25,
                    sort = {"expectedCloseDate", "dealId"},
                    direction = Sort.Direction.ASC) Pageable pageable) {

        boolean paginationRequested = page != null || size != null;

        return paginationRequested
                ? dealService.listPage(status, stage, ownerId, pageable)
                : dealService.listAll(status, stage, ownerId, pageable.getSort());
    }

    /** GET /api/v1/deals/{dealId} - the deal plus line items, contacts, team, activities, attachments. */
    @GetMapping("/{dealId}")
    public DealDetailResponse get(@PathVariable String dealId) {
        return dealService.getDetail(dealId);
    }

    /** GET /api/v1/deals/{dealId}/story - the audit timeline. */
    @GetMapping("/{dealId}/story")
    public DealStoryResponse story(@PathVariable String dealId) {
        return dealService.getStory(dealId);
    }

    /** POST /api/v1/deals */
    @PostMapping
    public ResponseEntity<DealResponse> create(@Valid @RequestBody DealCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dealService.create(request));
    }

    /** PUT /api/v1/deals/{dealId} - fields, owner, team, financials. Stage changes go elsewhere. */
    @PutMapping("/{dealId}")
    public DealResponse update(@PathVariable String dealId,
                               @Valid @RequestBody DealUpdateRequest request) {
        return dealService.update(dealId, request);
    }

    /**
     * DELETE /api/v1/deals/{dealId}
     *
     * <p>204 with no body. A deleted resource has no representation to return, and sending the row
     * back invites a client to treat it as still present.
     */
    @DeleteMapping("/{dealId}")
    public ResponseEntity<Void> delete(@PathVariable String dealId) {
        dealService.delete(dealId);
        return ResponseEntity.noContent().build();
    }

    /** POST /api/v1/deals/{dealId}/contacts */
    @PostMapping("/{dealId}/contacts")
    public ResponseEntity<DealContactResponse> attachContact(
            @PathVariable String dealId,
            @Valid @RequestBody DealContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dealService.attachContact(dealId, request));
    }

    /** DELETE /api/v1/deals/{dealId}/contacts/{contactId} */
    @DeleteMapping("/{dealId}/contacts/{contactId}")
    public ResponseEntity<Void> detachContact(@PathVariable String dealId,
                                              @PathVariable String contactId) {
        dealService.detachContact(dealId, contactId);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1/deals/{dealId}/line-items
     *
     * <p>Returns the whole deal detail rather than the created line item. Adding a line changes the
     * deal's value, so returning only the line would leave the client holding a stale total it has
     * no way to know is stale - and the deal value is the number every screen shows.
     */
    @PostMapping("/{dealId}/line-items")
    public ResponseEntity<DealDetailResponse> addLineItem(
            @PathVariable String dealId,
            @Valid @RequestBody LineItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dealService.addLineItem(dealId, request));
    }

    /** PUT /api/v1/deals/{dealId}/line-items/{lineItemId} */
    @PutMapping("/{dealId}/line-items/{lineItemId}")
    public DealDetailResponse updateLineItem(@PathVariable String dealId,
                                             @PathVariable String lineItemId,
                                             @Valid @RequestBody LineItemRequest request) {
        return dealService.updateLineItem(dealId, lineItemId, request);
    }

    /**
     * DELETE /api/v1/deals/{dealId}/line-items/{lineItemId}
     *
     * <p>200 with the deal detail, not 204. Same reasoning as the add: the deal value changed, and a
     * bare 204 would leave the client to guess the new total or re-fetch.
     */
    @DeleteMapping("/{dealId}/line-items/{lineItemId}")
    public DealDetailResponse deleteLineItem(@PathVariable String dealId,
                                             @PathVariable String lineItemId) {
        return dealService.deleteLineItem(dealId, lineItemId);
    }

    /** PUT /api/v1/deals/{dealId}/stage - move stage; status and probability follow automatically. */
    @PutMapping("/{dealId}/stage")
    public DealResponse moveToStage(@PathVariable String dealId,
                                    @Valid @RequestBody StageChangeRequest request) {
        return dealService.moveToStage(dealId, request);
    }

    /** PUT /api/v1/deals/{dealId}/lost - mark Closed Lost with a required reason. */
    @PutMapping("/{dealId}/lost")
    public DealResponse markLost(@PathVariable String dealId,
                                 @Valid @RequestBody DealLostRequest request) {
        return dealService.markLost(dealId, request);
    }
}
