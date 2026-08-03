package com.closemore.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Inbound body for adding or updating a line item.
 *
 * <p><b>totalLineValue is absent on purpose.</b> The column exists and is NOT NULL, but the server
 * computes it: quantity x unitPriceAtSale - discountAmount. Accepting it from the client would let
 * the stored total disagree with the numbers it is supposedly derived from, and since the deal value
 * is the sum of those totals, one bad line item silently corrupts the deal's value and every
 * forecast built on it.
 *
 * <p>quantity has a floor of 1 rather than 0: a line item for nothing is not a line item, and zero
 * quantity with a non-zero discount produces a negative total.
 */
public record LineItemRequest(
        @NotBlank(message = "productId is required") String productId,
        @Min(value = 1, message = "quantity must be at least 1") int quantity,
        @PositiveOrZero(message = "unitPriceAtSale cannot be negative") double unitPriceAtSale,
        @PositiveOrZero(message = "discountAmount cannot be negative") double discountAmount
) {
}
