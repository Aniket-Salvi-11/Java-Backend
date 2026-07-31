package com.closemore.backend.mapper;

import com.closemore.backend.domain.ContactEntity;
import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.LineItemEntity;
import com.closemore.backend.domain.PipelineEntity;
import com.closemore.backend.domain.ProductEntity;
import com.closemore.backend.domain.UserEntity;
import com.closemore.backend.dto.ContactResponse;
import com.closemore.backend.dto.DealResponse;
import com.closemore.backend.dto.LineItemResponse;
import com.closemore.backend.dto.PipelineResponse;
import com.closemore.backend.dto.ProductResponse;
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
}