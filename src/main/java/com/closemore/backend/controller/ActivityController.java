package com.closemore.backend.controller;

import com.closemore.backend.dto.ActivityCreateRequest;
import com.closemore.backend.dto.ActivityResponse;
import com.closemore.backend.dto.ActivityUpdateRequest;
import com.closemore.backend.service.ActivityService;
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
 * Activities - notes, calls and meetings logged against a deal or contact.
 *
 * <p>Thin by design: binding and nothing else. Nothing here is on
 * {@code JwtAuthenticationFilter.PUBLIC_PATH_PREFIXES}, so a request without a valid token never
 * reaches these methods.
 *
 * <p><b>The list endpoint returns two different shapes.</b> Called plainly it returns a bare JSON
 * array, exactly as the Next.js backend does. Called with {@code ?page=} or {@code ?size=} it
 * returns a PageResponse envelope. See PageResponse for the full reasoning.
 *
 * <p>The default sort is newest first. Activities are a timeline, and a timeline that opens on the
 * oldest entry is one nobody wants to read; {@code logId} is the tiebreaker, because Log_Date is a
 * date with no time component and several entries a day is normal.
 */
@RestController
@RequestMapping("/api/v1/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    /** GET /api/v1/activities - filter by parent, type and date range; sort; optionally paginate. */
    @GetMapping
    public Object list(
            @RequestParam(name = "parentObjectType", required = false) String parentObjectType,
            @RequestParam(name = "parentObjectId", required = false) String parentObjectId,
            @RequestParam(name = "activityType", required = false) String activityType,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @PageableDefault(size = 25,
                    sort = {"logDate", "logId"},
                    direction = Sort.Direction.DESC) Pageable pageable) {

        boolean paginationRequested = page != null || size != null;

        return paginationRequested
                ? activityService.listPage(parentObjectType, parentObjectId, activityType,
                        fromDate, toDate, pageable)
                : activityService.listAll(parentObjectType, parentObjectId, activityType,
                        fromDate, toDate, pageable.getSort());
    }

    /** POST /api/v1/activities - fires the AI ingestion event when the activity is a Note. */
    @PostMapping
    public ResponseEntity<ActivityResponse> create(
            @Valid @RequestBody ActivityCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(activityService.create(request));
    }

    /** PUT /api/v1/activities/{logId} */
    @PutMapping("/{logId}")
    public ActivityResponse update(@PathVariable String logId,
                                   @Valid @RequestBody ActivityUpdateRequest request) {
        return activityService.update(logId, request);
    }

    /** DELETE /api/v1/activities/{logId} - removes the activity, its attachment rows and their files. */
    @DeleteMapping("/{logId}")
    public ResponseEntity<Void> delete(@PathVariable String logId) {
        activityService.delete(logId);
        return ResponseEntity.noContent().build();
    }
}
