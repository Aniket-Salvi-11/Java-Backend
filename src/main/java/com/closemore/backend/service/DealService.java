package com.closemore.backend.service;

import com.closemore.backend.domain.ActivityAttachmentEntity;
import com.closemore.backend.domain.ActivityEntity;
import com.closemore.backend.domain.DealContactEntity;
import com.closemore.backend.domain.DealContactId;
import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.DealTeamMemberEntity;
import com.closemore.backend.domain.DealTeamMemberId;
import com.closemore.backend.domain.LineItemEntity;
import com.closemore.backend.dto.ActivityAttachmentResponse;
import com.closemore.backend.dto.ActivityResponse;
import com.closemore.backend.dto.DealContactRequest;
import com.closemore.backend.dto.DealContactResponse;
import com.closemore.backend.dto.DealCreateRequest;
import com.closemore.backend.dto.DealDetailResponse;
import com.closemore.backend.dto.DealLostRequest;
import com.closemore.backend.dto.DealResponse;
import com.closemore.backend.dto.DealStoryResponse;
import com.closemore.backend.dto.DealTeamMemberResponse;
import com.closemore.backend.dto.DealUpdateRequest;
import com.closemore.backend.dto.LineItemRequest;
import com.closemore.backend.dto.LineItemResponse;
import com.closemore.backend.dto.PageResponse;
import com.closemore.backend.dto.StageChangeRequest;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.rbac.Role;
import com.closemore.backend.repository.ActivityAttachmentRepository;
import com.closemore.backend.repository.ActivityRepository;
import com.closemore.backend.repository.DealContactRepository;
import com.closemore.backend.repository.DealRepository;
import com.closemore.backend.repository.DealTeamMemberRepository;
import com.closemore.backend.repository.EventLogRepository;
import com.closemore.backend.repository.LineItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The deals resource group - thirteen endpoints, and the most complex service in the port.
 *
 * <p><b>Class-level {@code @Transactional} is load-bearing.</b> TenantContextAspect only issues the
 * three {@code set_config} calls for transactional methods. A method here that is not transactional
 * gets no session variables and every RLS policy evaluates against unset settings, which returns an
 * empty result rather than an error.
 *
 * <p><b>Visibility here is wider than for contacts, and that is the trap.</b> V9 extended the deals
 * policy so a Sales_Rep can see a deal they are on the team of, not only one they own. So the
 * defence-in-depth check is {@link RbacService#requireOwnerOrTeamOrAdmin}, not requireOwnerOrAdmin,
 * and the list filter is {@link DealSpecifications#visibleTo}, not an owner equality. Using the
 * contacts pattern here would be stricter than the database and would make team deals vanish from
 * the list with no error anywhere.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DealService {

    private static final String OBJECT_TYPE = "Deal";
    private static final String ACTIVITY_PARENT_TYPE = "Deal";

    /**
     * Sort keys a caller may name. Financial columns are included because sorting a pipeline by
     * value is the single most common thing a sales manager does.
     */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "dealId", "dealName", "currentStage", "dealValue", "expectedCloseDate",
            "probabilityPercentage", "ownerId", "status", "dealSource", "pipelineId",
            "createdAt", "updatedAt", "arr", "tcv", "tlv");

    private final DealRepository dealRepository;
    private final LineItemRepository lineItemRepository;
    private final DealContactRepository dealContactRepository;
    private final DealTeamMemberRepository dealTeamMemberRepository;
    private final ActivityRepository activityRepository;
    private final ActivityAttachmentRepository activityAttachmentRepository;
    private final EventLogRepository eventLogRepository;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    // --- list -----------------------------------------------------------------------------

    /** GET /api/v1/deals with no {@code ?page=} - a bare array, matching the Next.js backend. */
    public List<DealResponse> listAll(String status, String stage, String ownerId, Sort sort) {
        AuthenticatedUser user = currentUserService.require();
        requireSortablePropertiesOnly(sort);

        return dealRepository.findAll(filterFor(user, status, stage, ownerId), sort).stream()
                .map(DtoMapper::toDealResponse)
                .toList();
    }

    /** GET /api/v1/deals?page=... - the opt-in paginated form. */
    public PageResponse<DealResponse> listPage(String status, String stage, String ownerId,
                                               Pageable pageable) {
        AuthenticatedUser user = currentUserService.require();
        requireSortablePropertiesOnly(pageable.getSort());

        Page<DealEntity> page =
                dealRepository.findAll(filterFor(user, status, stage, ownerId), pageable);
        return PageResponse.of(page, DtoMapper::toDealResponse);
    }

    // --- detail and story -------------------------------------------------------------------

    /** GET /api/v1/deals/{dealId} - the deal and everything hanging off it, in one transaction. */
    public DealDetailResponse getDetail(String dealId) {
        DealEntity deal = loadVisible(dealId);

        List<ActivityEntity> activities =
                activityRepository.findByParentObjectTypeAndParentObjectId(ACTIVITY_PARENT_TYPE, dealId);

        // Attachments reach the deal through the activity that carries them, so they are collected
        // per activity and flattened. A deal with no activities therefore has no attachments, which
        // is correct rather than an omission.
        List<ActivityAttachmentResponse> attachments = new ArrayList<>();
        for (ActivityEntity activity : activities) {
            activityAttachmentRepository.findByLogId(activity.getLogId()).stream()
                    .map(DtoMapper::toActivityAttachmentResponse)
                    .forEach(attachments::add);
        }

        return new DealDetailResponse(
                DtoMapper.toDealResponse(deal),
                lineItemsOf(dealId),
                contactsOf(dealId),
                teamOf(dealId),
                activities.stream().map(DtoMapper::toActivityResponse).toList(),
                attachments);
    }

    /**
     * GET /api/v1/deals/{dealId}/story - the audit timeline.
     *
     * <p>Loading the deal first is not redundant. Without it, an unauthorised caller would get an
     * empty timeline rather than a 404 - and an empty timeline is indistinguishable from a deal
     * nothing has happened to yet, so the caller learns nothing but neither does anyone reading the
     * logs.
     */
    public DealStoryResponse getStory(String dealId) {
        loadVisible(dealId);

        return new DealStoryResponse(
                dealId,
                eventLogRepository
                        .findByObjectTypeAndObjectId(OBJECT_TYPE, dealId, Sort.by("timestamp").ascending())
                        .stream().map(DtoMapper::toEventLogResponse).toList(),
                activityRepository
                        .findByParentObjectTypeAndParentObjectId(ACTIVITY_PARENT_TYPE, dealId)
                        .stream().map(DtoMapper::toActivityResponse).toList());
    }

    // --- create, update, delete --------------------------------------------------------------

    /** POST /api/v1/deals */
    public DealResponse create(DealCreateRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = new DealEntity();
        deal.setDealId(UUID.randomUUID().toString());
        deal.setOwnerId(resolveOwner(user, request.ownerId()));
        deal.setDealName(request.dealName());
        deal.setAssociatedContactId(request.associatedContactId());
        deal.setPipelineId(request.pipelineId());
        deal.setCurrentStage(request.currentStage());
        deal.setDealValue(round(request.dealValue()));
        deal.setExpectedCloseDate(request.expectedCloseDate());
        deal.setDealSource(request.dealSource());
        applyFinancials(deal, request.arr(), request.tcv(), request.tlv(),
                request.commission(), request.partnerCommission(), request.distributorCommission());

        // Status is derived, never accepted from the caller - see DealCreateRequest. Probability
        // follows the stage only when the stage is terminal; otherwise the caller's value stands.
        deal.setStatus(DealStageRules.statusForStage(request.currentStage()));
        Integer forcedProbability = DealStageRules.probabilityForStage(request.currentStage());
        deal.setProbabilityPercentage(
                forcedProbability != null ? forcedProbability : request.probabilityPercentage());

        // saveAndFlush, not save: the INSERT must reach the database inside this method so an RLS
        // refusal surfaces here rather than at commit, where it escapes this transaction's handling.
        DealEntity saved = dealRepository.saveAndFlush(deal);

        if (request.teamMemberIds() != null) {
            replaceTeam(saved.getDealId(), request.teamMemberIds());
        }

        DealResponse response = DtoMapper.toDealResponse(saved);
        auditService.logCreate(user, OBJECT_TYPE, saved.getDealId(), saved.getDealName(), response);
        return response;
    }

    /** PUT /api/v1/deals/{dealId} - fields, owner, team and financials. Not the stage. */
    public DealResponse update(String dealId, DealUpdateRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = loadVisible(dealId);
        rbacService.requireOwnerOrTeamOrAdmin(user, dealId, deal.getOwnerId());

        // Snapshot BEFORE mutating. The entity is managed, so every setter below is already
        // reflected in it - capture the prior state first or the audit entry records the new values
        // twice and the diff is lost permanently.
        DealResponse before = DtoMapper.toDealResponse(deal);

        deal.setDealName(request.dealName());
        deal.setAssociatedContactId(request.associatedContactId());
        deal.setPipelineId(request.pipelineId());
        deal.setExpectedCloseDate(request.expectedCloseDate());
        deal.setProbabilityPercentage(request.probabilityPercentage());
        deal.setDealSource(request.dealSource());
        deal.setWinLossReason(request.winLossReason());
        applyFinancials(deal, request.arr(), request.tcv(), request.tlv(),
                request.commission(), request.partnerCommission(), request.distributorCommission());

        // Only an Admin may reassign a deal, and only when a new owner is actually named. Null means
        // "not editing ownership" - see DealUpdateRequest.
        if (request.ownerId() != null && !request.ownerId().isBlank()
                && user.role() == Role.ADMIN) {
            deal.setOwnerId(request.ownerId());
        }

        // The client's dealValue only survives while the deal has no line items. Once any exist the
        // value is their sum, because two sources of truth for the same number always diverge.
        List<LineItemEntity> lineItems = lineItemRepository.findByDealId(dealId);
        deal.setDealValue(lineItems.isEmpty() ? round(request.dealValue()) : sumOf(lineItems));

        DealEntity saved = dealRepository.saveAndFlush(deal);

        if (request.teamMemberIds() != null) {
            replaceTeam(dealId, request.teamMemberIds());
        }

        DealResponse after = DtoMapper.toDealResponse(saved);
        auditService.logUpdate(user, OBJECT_TYPE, dealId, saved.getDealName(), before, after);
        return after;
    }

    /**
     * DELETE /api/v1/deals/{dealId}
     *
     * <p>line_items, deal_contacts and deal_team_members carry ON DELETE CASCADE, so the database
     * removes them. Activities do NOT: they reference the deal through the soft polymorphic pair
     * (Parent_Object_Type, Parent_Object_ID) with no foreign key, so nothing cascades and deleting
     * the deal alone would leave orphan activities pointing at an id that no longer resolves. They
     * are deleted explicitly here, attachments first, because the attachment table does have a real
     * foreign key to the activity.
     */
    public void delete(String dealId) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = loadVisible(dealId);
        rbacService.requireOwnerOrTeamOrAdmin(user, dealId, deal.getOwnerId());

        // The audit entry is the only surviving copy of this row once the transaction commits.
        DealResponse before = DtoMapper.toDealResponse(deal);
        String dealName = deal.getDealName();

        List<ActivityEntity> activities =
                activityRepository.findByParentObjectTypeAndParentObjectId(ACTIVITY_PARENT_TYPE, dealId);
        for (ActivityEntity activity : activities) {
            List<ActivityAttachmentEntity> attachments =
                    activityAttachmentRepository.findByLogId(activity.getLogId());
            activityAttachmentRepository.deleteAll(attachments);
        }
        activityRepository.deleteAll(activities);

        dealRepository.delete(deal);
        dealRepository.flush();

        auditService.logDelete(user, OBJECT_TYPE, dealId, dealName, before);
    }

    // --- contacts ---------------------------------------------------------------------------

    /** POST /api/v1/deals/{dealId}/contacts */
    public DealContactResponse attachContact(String dealId, DealContactRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = loadVisible(dealId);
        rbacService.requireOwnerOrTeamOrAdmin(user, dealId, deal.getOwnerId());

        DealContactEntity link = new DealContactEntity();
        link.setId(new DealContactId(dealId, request.contactId()));
        link.setPrimary(request.primary());

        DealContactEntity saved = dealContactRepository.saveAndFlush(link);
        DealContactResponse response = DtoMapper.toDealContactResponse(saved);

        auditService.logUpdate(user, OBJECT_TYPE, dealId, deal.getDealName(), null, response);
        return response;
    }

    /** DELETE /api/v1/deals/{dealId}/contacts/{contactId} */
    public void detachContact(String dealId, String contactId) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = loadVisible(dealId);
        rbacService.requireOwnerOrTeamOrAdmin(user, dealId, deal.getOwnerId());

        DealContactId id = new DealContactId(dealId, contactId);
        DealContactEntity link = dealContactRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Contact is not attached to this deal"));

        DealContactResponse before = DtoMapper.toDealContactResponse(link);
        dealContactRepository.delete(link);
        dealContactRepository.flush();

        auditService.logUpdate(user, OBJECT_TYPE, dealId, deal.getDealName(), before, null);
    }

    // --- line items -------------------------------------------------------------------------

    /** POST /api/v1/deals/{dealId}/line-items - adds a line and recalculates the deal value. */
    public DealDetailResponse addLineItem(String dealId, LineItemRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = loadVisible(dealId);
        rbacService.requireOwnerOrTeamOrAdmin(user, dealId, deal.getOwnerId());
        DealResponse before = DtoMapper.toDealResponse(deal);

        LineItemEntity item = new LineItemEntity();
        item.setLineItemId(UUID.randomUUID().toString());
        item.setDealId(dealId);
        applyLineItem(item, request);
        lineItemRepository.saveAndFlush(item);

        recalculateDealValue(deal);
        auditService.logUpdate(user, OBJECT_TYPE, dealId, deal.getDealName(),
                before, DtoMapper.toDealResponse(deal));

        return getDetail(dealId);
    }

    /** PUT /api/v1/deals/{dealId}/line-items/{lineItemId} */
    public DealDetailResponse updateLineItem(String dealId, String lineItemId,
                                             LineItemRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = loadVisible(dealId);
        rbacService.requireOwnerOrTeamOrAdmin(user, dealId, deal.getOwnerId());
        DealResponse before = DtoMapper.toDealResponse(deal);

        LineItemEntity item = loadLineItemOfDeal(dealId, lineItemId);
        applyLineItem(item, request);
        lineItemRepository.saveAndFlush(item);

        recalculateDealValue(deal);
        auditService.logUpdate(user, OBJECT_TYPE, dealId, deal.getDealName(),
                before, DtoMapper.toDealResponse(deal));

        return getDetail(dealId);
    }

    /** DELETE /api/v1/deals/{dealId}/line-items/{lineItemId} */
    public DealDetailResponse deleteLineItem(String dealId, String lineItemId) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = loadVisible(dealId);
        rbacService.requireOwnerOrTeamOrAdmin(user, dealId, deal.getOwnerId());
        DealResponse before = DtoMapper.toDealResponse(deal);

        LineItemEntity item = loadLineItemOfDeal(dealId, lineItemId);
        lineItemRepository.delete(item);
        lineItemRepository.flush();

        recalculateDealValue(deal);
        auditService.logUpdate(user, OBJECT_TYPE, dealId, deal.getDealName(),
                before, DtoMapper.toDealResponse(deal));

        return getDetail(dealId);
    }

    // --- stage transitions --------------------------------------------------------------------

    /**
     * PUT /api/v1/deals/{dealId}/stage - moves the deal and applies whatever the new stage implies.
     *
     * <p>The won/lost determination lives in {@link DealStageRules} rather than here, because the
     * migration plan records that this comparison exists in at least three places in the Next.js
     * codebase with slightly different implementations. This is the one place it lives now.
     */
    public DealResponse moveToStage(String dealId, StageChangeRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = loadVisible(dealId);
        rbacService.requireOwnerOrTeamOrAdmin(user, dealId, deal.getOwnerId());
        DealResponse before = DtoMapper.toDealResponse(deal);

        deal.setCurrentStage(request.currentStage());
        deal.setStatus(DealStageRules.statusForStage(request.currentStage()));

        Integer forcedProbability = DealStageRules.probabilityForStage(request.currentStage());
        if (forcedProbability != null) {
            deal.setProbabilityPercentage(forcedProbability);
        }
        if (request.winLossReason() != null && !request.winLossReason().isBlank()) {
            deal.setWinLossReason(request.winLossReason());
        }

        DealEntity saved = dealRepository.saveAndFlush(deal);
        DealResponse after = DtoMapper.toDealResponse(saved);

        auditService.logUpdate(user, OBJECT_TYPE, dealId, saved.getDealName(), before, after);
        return after;
    }

    /**
     * PUT /api/v1/deals/{dealId}/lost - a shortcut for the terminal case that always carries a reason.
     *
     * <p>Separate from the stage endpoint even though it could be expressed through it, because a
     * required reason is the entire point: a lost deal with no recorded reason is the one audit
     * record nobody can reconstruct later.
     */
    public DealResponse markLost(String dealId, DealLostRequest request) {
        AuthenticatedUser user = currentUserService.require();
        rbacService.blockExecutiveWrites(user);

        DealEntity deal = loadVisible(dealId);
        rbacService.requireOwnerOrTeamOrAdmin(user, dealId, deal.getOwnerId());
        DealResponse before = DtoMapper.toDealResponse(deal);

        deal.setCurrentStage(DealStageRules.STATUS_CLOSED_LOST);
        deal.setStatus(DealStageRules.STATUS_CLOSED_LOST);
        deal.setProbabilityPercentage(0);
        deal.setWinLossReason(request.winLossReason());

        DealEntity saved = dealRepository.saveAndFlush(deal);
        DealResponse after = DtoMapper.toDealResponse(saved);

        auditService.logUpdate(user, OBJECT_TYPE, dealId, saved.getDealName(), before, after);
        return after;
    }

    // --- helpers --------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Specification<DealEntity> filterFor(AuthenticatedUser user, String status,
                                                String stage, String ownerId) {
        Specification<DealEntity> visibility =
                rbacService.canViewAll(user) ? null : DealSpecifications.visibleTo(user.userId());

        return DealSpecifications.allOf(
                visibility,
                DealSpecifications.hasStatus(status),
                DealSpecifications.hasStage(stage),
                DealSpecifications.hasOwner(ownerId));
    }

    /**
     * Loads a deal or throws 404.
     *
     * <p>RLS has already decided this. A deal belonging to another tenant, or to another rep with no
     * team membership, is not returned at all - see {@link ResourceNotFoundException} for why 404
     * rather than 403 is the correct answer.
     */
    private DealEntity loadVisible(String dealId) {
        return dealRepository.findById(dealId)
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found"));
    }

    /**
     * Loads a line item and checks it belongs to the deal in the path.
     *
     * <p>The ownership check is not ceremony. Without it, a caller with access to deal A could edit
     * a line item of deal B simply by naming it, and the line_items policy would allow it - the
     * policy authorises the ROW through its own deal, and it has no way to know which deal the
     * caller claimed in the URL.
     */
    private LineItemEntity loadLineItemOfDeal(String dealId, String lineItemId) {
        LineItemEntity item = lineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Line item not found"));

        if (!dealId.equals(item.getDealId())) {
            throw new ResourceNotFoundException("Line item not found");
        }
        return item;
    }

    private void applyLineItem(LineItemEntity item, LineItemRequest request) {
        item.setProductId(request.productId());
        item.setQuantity(request.quantity());
        item.setUnitPriceAtSale(request.unitPriceAtSale());
        item.setDiscountAmount(request.discountAmount());
        // Computed, never accepted from the client - see LineItemRequest.
        item.setTotalLineValue(
                round(request.quantity() * request.unitPriceAtSale() - request.discountAmount()));
    }

    /**
     * Recomputes the deal value from its line items.
     *
     * <p>Deliberately narrow: ARR, TCV, TLV and the three commission fields are NOT touched. The
     * Next.js backend's formulas for those are not documented anywhere this port can see, and
     * inventing them would silently produce wrong revenue figures that look plausible. They stay
     * caller-supplied through PUT /api/v1/deals/{id} until someone confirms the rules. Recorded as
     * an open item rather than guessed at.
     */
    private void recalculateDealValue(DealEntity deal) {
        deal.setDealValue(sumOf(lineItemRepository.findByDealId(deal.getDealId())));
        dealRepository.saveAndFlush(deal);
    }

    private double sumOf(List<LineItemEntity> lineItems) {
        double total = 0;
        for (LineItemEntity item : lineItems) {
            total += item.getTotalLineValue();
        }
        return round(total);
    }

    /**
     * Rounds to two decimal places.
     *
     * <p>The columns are DOUBLE PRECISION, so 0.1 + 0.2 stores as 0.30000000000000004 and a deal
     * value assembled from enough line items drifts visibly. Rounding at every write keeps the
     * stored figure equal to the one a person would compute. HALF_UP because that is what an
     * accountant means by rounding, unlike Java's default.
     */
    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private void applyFinancials(DealEntity deal, double arr, double tcv, double tlv,
                                 double commission, double partnerCommission,
                                 double distributorCommission) {
        deal.setArr(round(arr));
        deal.setTcv(round(tcv));
        deal.setTlv(round(tlv));
        deal.setCommission(round(commission));
        deal.setPartnerCommission(round(partnerCommission));
        deal.setDistributorCommission(round(distributorCommission));
    }

    /**
     * Replaces the deal's team wholesale.
     *
     * <p>Delete-then-insert rather than a diff. The team is small, the composite key makes a diff
     * fiddly, and a wrong diff leaves a member who should have been removed still holding visibility
     * of the deal - a silent authorisation bug rather than a visible one. The duplicate-stripping
     * matters for the same reason the flush does: a repeated id would violate the composite primary
     * key and surface as a 409 for a request that is not really in conflict with anything.
     */
    private void replaceTeam(String dealId, List<String> memberIds) {
        dealTeamMemberRepository.deleteAll(dealTeamMemberRepository.findById_DealId(dealId));
        dealTeamMemberRepository.flush();

        for (String userId : new LinkedHashSet<>(memberIds)) {
            if (userId == null || userId.isBlank()) {
                continue;
            }
            DealTeamMemberEntity member = new DealTeamMemberEntity();
            member.setId(new DealTeamMemberId(dealId, userId));
            dealTeamMemberRepository.save(member);
        }
        dealTeamMemberRepository.flush();
    }

    private String resolveOwner(AuthenticatedUser user, String requestedOwnerId) {
        boolean canAssign = user.role() == Role.ADMIN;
        return canAssign && requestedOwnerId != null && !requestedOwnerId.isBlank()
                ? requestedOwnerId
                : user.userId();
    }

    private List<LineItemResponse> lineItemsOf(String dealId) {
        return lineItemRepository.findByDealId(dealId).stream()
                .map(DtoMapper::toLineItemResponse)
                .toList();
    }

    private List<DealContactResponse> contactsOf(String dealId) {
        return dealContactRepository.findById_DealId(dealId).stream()
                .map(DtoMapper::toDealContactResponse)
                .toList();
    }

    private List<DealTeamMemberResponse> teamOf(String dealId) {
        return dealTeamMemberRepository.findById_DealId(dealId).stream()
                .map(DtoMapper::toDealTeamMemberResponse)
                .toList();
    }

    private void requireSortablePropertiesOnly(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty() + "'");
            }
        }
    }
}
