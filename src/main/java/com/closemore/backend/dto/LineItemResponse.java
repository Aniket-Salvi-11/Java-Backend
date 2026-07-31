package com.closemore.backend.dto;

/** Outbound DTO for a deal line item. */
public record LineItemResponse(
        String lineItemId,
        String dealId,
        String productId,
        int quantity,
        double unitPriceAtSale,
        double discountAmount,
        double totalLineValue
) {
}
