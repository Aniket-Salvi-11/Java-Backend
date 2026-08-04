package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * POST /api/v1/products - Admin only.
 *
 * <p>{@code isActive} is a Boolean wrapper rather than a primitive so "absent" and "false" are
 * distinguishable: absent means default to active, which is what the column default does. The
 * entity field stays primitive - see ProductEntity for why.
 *
 * <p>Every column on products is NOT NULL, so every field here except {@code isActive} is required.
 */
public record ProductCreateRequest(
        @NotBlank(message = "name is required") String name,
        @NotBlank(message = "skuCode is required") String skuCode,
        @NotBlank(message = "type is required") String type,
        @PositiveOrZero(message = "unitPrice cannot be negative") double unitPrice,
        @NotBlank(message = "description is required") String description,
        Boolean isActive) {
}
