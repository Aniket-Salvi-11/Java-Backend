package com.closemore.backend.service;

import com.closemore.backend.domain.ActivityAttachmentEntity;
import com.closemore.backend.domain.ActivityEntity;
import com.closemore.backend.dto.ActivityCreateRequest;
import com.closemore.backend.dto.ActivityResponse;
import com.closemore.backend.dto.ActivityUpdateRequest;
import com.closemore.backend.dto.PageResponse;
import com.closemore.backend.ingestion.IngestionEvent;
import com.closemore.backend.ingestion.IngestionEventGateway;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.repository.ActivityAttachmentRepository;
import com.closemore.backend.repository.ActivityRepository;
import com.closemore.backend.repository.ContactRepository;
import com.closemore.backend.repository.DealRepository;
import com.closemore.backend.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The activities resource group - notes, calls and meetings logged against a deal or a contact.
 *
 * <p><b>This is the first service that publishes outward.</b> Section 10 of the migration plan
 * describes the AI ingestion pipeline hooking in at exactly two points, and note creation is one of
 * them. Everything before this tranche only read from and wrote to the database; from here on a
 * write can also put a message on a queue that a system outside this repository consumes.
 *
 * <p><b>Visibility here is wider and messier than for deals.</b> An activity is readable by its
 * logger, by Admin and Executive, and by the owner of the parent deal or contact - four routes,
 * two of which need a join. That is left entirely to RLS rather than mirrored in code: reproducing
 * it in Criteria would be a lot of code whose only job is to agree with the database, and drift
 * would narrow what a user sees with no error anywhere. The write-side authorship check below is the
 * half of defence in depth that actually earns its keep.
 *
 * <p><b>Deal team members are deliberately absent from that list.</b> A team member can open a deal
 * and see none of its activity history. It looks like an oversight in the Next.js original; it is
 * recorded and pinned by a test rather than fixed, because changing it here would be a redesign
 * shipped inside a port.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ActivityService {

    private static final String OBJECT_TYPE = "Activity";

    /**
     * The parent types the RLS policy knows about.
     *
     * <p>The column is free text with no foreign key, so nothing in the database stops an activity
     * being written against 'Invoice' or a typo'd 'deal'. The policy's visibility clauses only cover
     * 'Deal' and 'Contact', so such a row would be readable by its logger and by Admins and by
     * nobody else - invisible to the very people who should see it, with no error at any point.
     * Rejecting up front is the only way that failure ever gets noticed.
     */
    private static final String PARENT_DEAL = "Deal";
    private static final String PARENT_CONTACT = "Contact";
    private static final Set<String> VALID_PARENT_TYPES = Set.of(PARENT_DEAL, PARENT_CONTACT);

    /** Activity types that represent a written note, and therefore feed the AI pipeline. */
    private static final Set<String> NOTE_TYPES = Set.of("Note", "note", "NOTE");

    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "logId", "parentObjectType", "parentObjectId", "activityType", "summary",
            "followUpDate", "logDate", "loggedByUserId", "createdAt", "updatedAt");

    private final ActivityRepository activityRepository;
    private final ActivityAttachmentRepository activityAttachmentRepository;
    private final DealRepository dealRepository;
    private final ContactRepository contactRepository;
    private final StorageProvider storageProvider;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final IngestionEventGateway ingestionEvents;

    // --- list -----------------------------------------------------------------------------

    /** GET /api/v1/activities with no {@code ?page=} - a bare array, matching the Next.js backend. */
    public List<ActivityResponse> listAll(String parentType, String parentId, String activityType,
                                          String fromDate, String toDate, Sort sort) {
        currentUserService.require();
        requireSortablePropertiesOnly(sort);

        return activityRepository
                .findAll(filter(parentType, parentId, activityType, fromDate, toDate), sort)
                .stream().map(DtoMapper::toActivityResponse).toList();
    }

    /** GET /api/v1/activities?page=... - the opt-in paginated form. */
    public PageResponse<ActivityResponse> listPage(String parentType, String parentId,
                                                   String activityType, String fromDate,
                                                   String toDate, Pageable pageable) {
        currentUserService.require();
        requireSortablePropertiesOnly(pageable.getSort());

        Page<ActivityEntity> page = activityRepository
                .findAll(filter(parentType, parentId, activityType, fromDate, toDate), pageable);
        return PageResponse.of(page, DtoMapper::toActivityResponse);
    }

    // --- writes ---------------------------------------------------------------------------

    /**
     * POST /api/v1/activities - logs a note, call or meeting, and fires the ingestion event for notes.
     */
    public ActivityResponse create(ActivityCreateRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        String parentType = request.parentObjectType();
        if (!VALID_PARENT_TYPES.contains(parentType)) {
            throw new BadRequestException(
                    "parentObjectType must be one of " + VALID_PARENT_TYPES);
        }
        requireVisibleParent(parentType, request.parentObjectId());

        ActivityEntity activity = new ActivityEntity();
        activity.setLogId(UUID.randomUUID().toString());
        activity.setParentObjectType(parentType);
        activity.setParentObjectId(request.parentObjectId());
        activity.setActivityType(request.activityType());
        activity.setSummary(request.summary());
        activity.setDetailedDescription(request.detailedDescription());
        activity.setFollowUpDate(request.followUpDate());
        activity.setLogDate(request.logDate() == null || request.logDate().isBlank()
                ? LocalDate.now().toString()
                : request.logDate());
        // Always the caller. Accepting this from the request would let one user write history
        // attributed to another - and since the policy derives tenancy from this column, it would
        // also be a tenancy bypass rather than merely a lie in the audit trail.
        activity.setLoggedByUserId(user.userId());

        // saveAndFlush, not save: the INSERT must reach the database inside this method so an RLS
        // refusal surfaces here rather than at commit, where it escapes this transaction's handling.
        ActivityEntity saved = activityRepository.saveAndFlush(activity);
        ActivityResponse response = DtoMapper.toActivityResponse(saved);

        auditService.logCreate(user, OBJECT_TYPE, saved.getLogId(), saved.getSummary(), response);

        if (isNote(saved.getActivityType())) {
            // Queued for dispatch after this transaction commits, never before - see
            // IngestionEventGateway for why publishing inside the transaction is the bug.
            ingestionEvents.publishAfterCommit(new IngestionEvent(
                    IngestionEvent.NOTE_CREATED,
                    OBJECT_TYPE,
                    saved.getLogId(),
                    saved.getParentObjectType(),
                    saved.getParentObjectId(),
                    currentTenant(),
                    user.userId(),
                    OffsetDateTime.now()));
        }

        return response;
    }

    /** PUT /api/v1/activities/{logId} - summary, description and follow-up date only. */
    public ActivityResponse update(String logId, ActivityUpdateRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        ActivityEntity activity = loadVisible(logId);
        // Authorship, not parent ownership. A deal owner can READ every activity on their deal
        // through the policy, but editing somebody else's note would rewrite their words under
        // their name - so the write check is deliberately narrower than the read.
        rbacService.requireOwnerOrAdmin(user, activity.getLoggedByUserId());

        // Snapshot BEFORE mutating. The entity is managed, so the setters below are already
        // reflected in it - capture first or the audit entry records the new values twice and the
        // diff is lost permanently.
        ActivityResponse before = DtoMapper.toActivityResponse(activity);

        activity.setSummary(request.summary());
        activity.setDetailedDescription(request.detailedDescription());
        activity.setFollowUpDate(request.followUpDate());

        ActivityEntity saved = activityRepository.saveAndFlush(activity);
        ActivityResponse after = DtoMapper.toActivityResponse(saved);

        auditService.logUpdate(user, OBJECT_TYPE, logId, saved.getSummary(), before, after);
        return after;
    }

    /**
     * DELETE /api/v1/activities/{logId} - removes the activity and its attachments.
     *
     * <p>activity_attachments has a real foreign key to activities, so the rows must go first
     * regardless. The stored files are removed too: nothing else ever will, and an upload endpoint
     * whose deletes leave the bytes behind is a disk that fills up quietly over a year.
     */
    public void delete(String logId) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        ActivityEntity activity = loadVisible(logId);
        rbacService.requireOwnerOrAdmin(user, activity.getLoggedByUserId());

        // The audit entry is the only surviving copy of this row once the transaction commits.
        ActivityResponse before = DtoMapper.toActivityResponse(activity);
        String summary = activity.getSummary();

        List<ActivityAttachmentEntity> attachments = activityAttachmentRepository.findByLogId(logId);
        for (ActivityAttachmentEntity attachment : attachments) {
            // Deliberately before the database delete. A file left on disk after a failed
            // transaction is orphaned bytes; a database row pointing at a file already removed is a
            // download endpoint that 500s, which is the worse of the two.
            storageProvider.delete(attachment.getStoragePath());
        }
        activityAttachmentRepository.deleteAll(attachments);
        activityRepository.delete(activity);
        activityRepository.flush();

        auditService.logDelete(user, OBJECT_TYPE, logId, summary, before);
    }

    // --- helpers --------------------------------------------------------------------------

    /**
     * Loads an activity or throws 404.
     *
     * <p>RLS has already decided this: an activity the caller has no route to is not returned at
     * all - see {@link ResourceNotFoundException} for why 404 rather than 403 is correct.
     */
    ActivityEntity loadVisible(String logId) {
        return activityRepository.findById(logId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity not found"));
    }

    /**
     * Refuses to log an activity against a parent the caller cannot see.
     *
     * <p>Without this the insert would succeed. The activities WITH CHECK clause validates the
     * LOGGER's organisation, not the parent's - so a rep could attach a note to any deal id they
     * could guess, including another tenant's, and the note would then be visible to that deal's
     * owner. Reading the parent through the normal path makes its own policy do the work.
     */
    private void requireVisibleParent(String parentType, String parentId) {
        boolean visible = PARENT_DEAL.equals(parentType)
                ? dealRepository.findById(parentId).isPresent()
                : contactRepository.findById(parentId).isPresent();

        if (!visible) {
            // 404 rather than 400 or 403: the caller learns that this id is not theirs to use, and
            // nothing more. A distinct error here would confirm which ids exist.
            throw new ResourceNotFoundException(parentType + " not found");
        }
    }

    private Specification<ActivityEntity> filter(String parentType, String parentId,
                                                 String activityType, String fromDate,
                                                 String toDate) {
        return ActivitySpecifications.allOf(
                ActivitySpecifications.hasParent(parentType, parentId),
                ActivitySpecifications.hasType(activityType),
                ActivitySpecifications.loggedFrom(fromDate),
                ActivitySpecifications.loggedTo(toDate));
    }

    private boolean isNote(String activityType) {
        return activityType != null && NOTE_TYPES.contains(activityType.trim());
    }

    /**
     * The organisation for the outgoing event.
     *
     * <p>Read from the request context rather than looked up, because the context is the same value
     * the session variables were set from - so the event's tenant is guaranteed to match the tenant
     * the row was written under, which is the property the consumer depends on.
     */
    private String currentTenant() {
        return currentUserService.currentTenant();
    }

    private void requireSortablePropertiesOnly(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty() + "'");
            }
        }
    }
}
