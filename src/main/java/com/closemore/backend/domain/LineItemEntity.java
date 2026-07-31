package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps the `line_items` table from V1__init.sql.
 *
 * <p><b>This table has no tenant column and no owner column.</b> Its entire visibility is derived
 * two hops away: line_items_rls_policy joins to deals, and from there to users, to compare the
 * DEAL owner's organisation against app.current_user_tenant. Nothing on this row says who may see
 * it.
 *
 * <p>That has a practical consequence worth remembering: a line item is visible exactly when its
 * parent deal is. A Sales_Rep in the right organisation who does not own the deal sees neither -
 * verified in DealsIsolationIT rather than assumed, because two-hop policies are the ones most
 * likely to be silently wrong.
 *
 * <p>Deal_ID and Product_ID are scalars, not associations - see DealEntity for the reasoning. Note
 * the FK on Deal_ID is ON DELETE CASCADE (unlike the RESTRICT used elsewhere), so deleting a deal
 * takes its line items with it.
 */
@Entity
@Table(name = "line_items")
@Getter
@Setter
@NoArgsConstructor
public class LineItemEntity {

    @Id
    @Column(name = "Line_Item_ID", nullable = false, updatable = false)
    private String lineItemId;

    /** FK -> deals(Deal_ID) ON DELETE CASCADE. The join the RLS policy walks. */
    @Column(name = "Deal_ID", nullable = false)
    private String dealId;

    /** FK -> products(Product_ID) ON DELETE RESTRICT. */
    @Column(name = "Product_ID", nullable = false)
    private String productId;

    @Column(name = "Quantity", nullable = false)
    private int quantity;

    /** Price captured at sale time - deliberately independent of products.Unit_Price today. */
    @Column(name = "Unit_Price_At_Sale", nullable = false)
    private double unitPriceAtSale;

    @Column(name = "Discount_Amount", nullable = false)
    private double discountAmount;

    @Column(name = "Total_Line_Value", nullable = false)
    private double totalLineValue;
}
