package com.closemore.backend.dto;

/** Outbound DTO for a product. See UserResponse for the entity-never-serialized rationale. */
public record ProductResponse(
        String productId,
        String name,
        String skuCode,
        String type,
        double unitPrice,
        String description,
        boolean isActive
) {
}
