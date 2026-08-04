package com.closemore.backend.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * PUT /api/v1/products/{productId} - Admin only. Null means "leave alone".
 *
 * <p>{@code unitPrice} is a Double wrapper here, unlike the create request: a primitive would
 * default to 0.0 on an omitted field and silently zero the price of every product it touched.
 */
public record ProductUpdateRequest(
        String name,
        String skuCode,
        String type,
        @PositiveOrZero(message = "unitPrice cannot be negative") Double unitPrice,
        String description,
        Boolean isActive) {
}
