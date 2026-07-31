package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps the `products` table from V1__init.sql.
 *
 * <p>GLOBAL REFERENCE DATA - deliberately has NO RLS policy. Every organisation reads the same
 * product catalogue, which is why {@code RlsWiringPreconditionsIT.TENANT_TABLES} does not list it
 * and why a query here returns rows even with no tenant context. If products ever gain an
 * organisation-owned column, that decision changes and this table needs a policy plus an entry in
 * that list.
 *
 * <p>Every column is NOT NULL, so numeric and boolean fields are primitives rather than wrappers:
 * a wrapper left null fails at insert time with a constraint violation, whereas a primitive
 * defaults to a value the column accepts.
 *
 * <p>NOTE the field is {@code active}, not {@code isActive}, even though the column is
 * "Is_Active". Lombok generates {@code isActive()} either way, but the resolved JavaBean property
 * differs: a field named {@code isActive} resolves to property "active" anyway, so a derived query
 * written as {@code findByIsActiveTrue()} fails at startup with "No property 'isActive' found".
 * Naming the field {@code active} makes the field, the property and the derived query agree.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class ProductEntity {

    @Id
    @Column(name = "Product_ID", nullable = false, updatable = false)
    private String productId;

    @Column(name = "Name", nullable = false)
    private String name;

    @Column(name = "SKU_Code", nullable = false)
    private String skuCode;

    @Column(name = "Type", nullable = false)
    private String type;

    @Column(name = "Unit_Price", nullable = false)
    private double unitPrice;

    @Column(name = "Description", nullable = false)
    private String description;

    /** Column default is true; Java's primitive default is false. Set explicitly when inserting. */
    @Column(name = "Is_Active", nullable = false)
    private boolean active;
}
