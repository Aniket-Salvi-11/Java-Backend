package com.closemore.backend.mapper;

import com.closemore.backend.domain.ActivityAttachmentEntity;
import com.closemore.backend.domain.ActivityEntity;
import com.closemore.backend.domain.CommentReactionEntity;
import com.closemore.backend.domain.ContactEntity;
import com.closemore.backend.domain.DealContactEntity;
import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.DealTeamMemberEntity;
import com.closemore.backend.domain.LineItemEntity;
import com.closemore.backend.domain.PipelineEntity;
import com.closemore.backend.domain.ProductEntity;
import com.closemore.backend.domain.TaskAttachmentEntity;
import com.closemore.backend.domain.TaskCommentEntity;
import com.closemore.backend.domain.TaskEntity;
import com.closemore.backend.domain.TaskNotificationEntity;
import com.closemore.backend.domain.UserEntity;
import com.closemore.backend.dto.ActivityAttachmentResponse;
import com.closemore.backend.dto.ActivityResponse;
import com.closemore.backend.dto.CommentReactionResponse;
import com.closemore.backend.dto.ContactResponse;
import com.closemore.backend.dto.DealContactResponse;
import com.closemore.backend.dto.DealResponse;
import com.closemore.backend.dto.DealTeamMemberResponse;
import com.closemore.backend.dto.LineItemResponse;
import com.closemore.backend.dto.PipelineResponse;
import com.closemore.backend.dto.ProductResponse;
import com.closemore.backend.dto.TaskAttachmentResponse;
import com.closemore.backend.dto.TaskCommentResponse;
import com.closemore.backend.dto.TaskNotificationResponse;
import com.closemore.backend.dto.TaskResponse;
import com.closemore.backend.dto.UserResponse;

/**
 * Hand-written entity -> DTO mapping. Plain static methods rather than MapStruct/ModelMapper on
 * purpose for Phase 1: no reflection or annotation processing to reason about, and the Password-drop
 * on users is visible right here in code rather than hidden in a generated class. Revisit if the
 * mapping surface grows large enough in Phase 3 to justify a library - with 16 tables to cover, that
 * conversation is worth having once the remaining entities land.
 */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static UserResponse toUserResponse(UserEntity e) {
        // Password intentionally not copied - it has no field on UserResponse.
        return new UserResponse(
                e.getUserId(),
                e.getFirstName(),
                e.getLastName(),
                e.getEmail(),
                e.getRole(),
                e.getStatus(),
                e.getPhoneNumber(),
                e.getOrganizationName(),
                e.getResidentialAddress(),
                e.getOfficeAddress(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    public static ContactResponse toContactResponse(ContactEntity e) {
        return new ContactResponse(
                e.getContactId(),
                e.getFirstName(),
                e.getLastName(),
                e.getEmail(),
                e.getPhonePrimary(),
                e.getOrganizationName(),
                e.getContactType(),
                e.getSource(),
                e.getCreatedDate(),
                e.getOwnerId(),
                e.getAvatarDataUrl(),
                e.getOrgAvatarDataUrl(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    public static ProductResponse toProductResponse(ProductEntity e) {
        return new ProductResponse(
                e.getProductId(),
                e.getName(),
                e.getSkuCode(),
                e.getType(),
                e.getUnitPrice(),
                e.getDescription(),
                e.isActive()
        );
    }

    public static PipelineResponse toPipelineResponse(PipelineEntity e) {
        return new PipelineResponse(
                e.getPipelineId(),
                e.getPipelineName(),
                e.getStagesJson()
        );
    }

    public static DealResponse toDealResponse(DealEntity e) {
        return new DealResponse(
                e.getDealId(),
                e.getDealName(),
                e.getAssociatedContactId(),
                e.getPipelineId(),
                e.getCurrentStage(),
                e.getDealValue(),
                e.getExpectedCloseDate(),
                e.getProbabilityPercentage(),
                e.getWinLossReason(),
                e.getOwnerId(),
                e.getStatus(),
                e.getDealSource(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getArr(),
                e.getTcv(),
                e.getTlv(),
                e.getCommission(),
                e.getPartnerCommission(),
                e.getDistributorCommission()
        );
    }

    public static LineItemResponse toLineItemResponse(LineItemEntity e) {
        return new LineItemResponse(
                e.getLineItemId(),
                e.getDealId(),
                e.getProductId(),
                e.getQuantity(),
                e.getUnitPriceAtSale(),
                e.getDiscountAmount(),
                e.getTotalLineValue()
        );
    }

    public static DealContactResponse toDealContactResponse(DealContactEntity e) {
        return new DealContactResponse(
                e.getId().getDealId(),
                e.getId().getContactId(),
                e.isPrimary()
        );
    }

    public static DealTeamMemberResponse toDealTeamMemberResponse(DealTeamMemberEntity e) {
        return new DealTeamMemberResponse(
                e.getId().getDealId(),
                e.getId().getUserId()
        );
    }

    public static ActivityResponse toActivityResponse(ActivityEntity e) {
        return new ActivityResponse(
                e.getLogId(),
                e.getParentObjectType(),
                e.getParentObjectId(),
                e.getActivityType(),
                e.getSummary(),
                e.getDetailedDescription(),
                e.getAttachmentUrl(),
                e.getFollowUpDate(),
                e.getLogDate(),
                e.getLoggedByUserId(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    public static ActivityAttachmentResponse toActivityAttachmentResponse(ActivityAttachmentEntity e) {
        return new ActivityAttachmentResponse(
                e.getAttachmentId(),
                e.getLogId(),
                e.getFileName(),
                e.getMimeType(),
                e.getFileSize(),
                e.getStoragePath(),
                e.getUploadedByUserId(),
                e.getUploadedAt()
        );
    }

    public static TaskResponse toTaskResponse(TaskEntity e) {
        return new TaskResponse(
                e.getTaskId(),
                e.getTaskTitle(),
                e.getDescription(),
                e.getAssignedTo(),
                e.getAssignedBy(),
                e.getDueDate(),
                e.getStatus(),
                e.isRead(),
                e.getCreatedAt()
        );
    }

    public static TaskAttachmentResponse toTaskAttachmentResponse(TaskAttachmentEntity e) {
        return new TaskAttachmentResponse(
                e.getAttachmentId(),
                e.getTaskId(),
                e.getFileName(),
                e.getMimeType(),
                e.getFileSize(),
                e.getStoragePath(),
                e.getUploadedBy(),
                e.getUploadedAt()
        );
    }

    public static TaskCommentResponse toTaskCommentResponse(TaskCommentEntity e) {
        return new TaskCommentResponse(
                e.getCommentId(),
                e.getTaskId(),
                e.getUserId(),
                e.getUserName(),
                e.getContent(),
                e.getAttachmentUrl(),
                e.getCreatedAt()
        );
    }

    public static CommentReactionResponse toCommentReactionResponse(CommentReactionEntity e) {
        return new CommentReactionResponse(
                e.getReactionId(),
                e.getCommentId(),
                e.getUserId(),
                e.getEmoji()
        );
    }

    public static TaskNotificationResponse toTaskNotificationResponse(TaskNotificationEntity e) {
        return new TaskNotificationResponse(
                e.getNotificationId(),
                e.getUserId(),
                e.getTaskId(),
                e.getMessage(),
                e.isRead(),
                e.getCreatedAt()
        );
    }
}