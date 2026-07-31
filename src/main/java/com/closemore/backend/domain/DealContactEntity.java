package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps the `deal_contacts` join table from V1__init.sql. First composite-key entity in the codebase.
 *
 * <p>@EmbeddedId rather than @IdClass: the key travels as one object, so repository lookups take a
 * DealContactId instead of an untyped pair, and derived queries reach through it as {@code id.dealId}.
 *
 * <p><b>Its RLS policy is the V9 version, not V4's.</b> V9__deal_teams.sql drops and recreates the
 * policies for deals, line_items and deal_contacts to add a team-membership clause. Reading V4
 * alone gives a materially wrong picture - it suggests only the owner and Admin/Executive can see a
 * deal's contacts, when in fact any team member on the parent deal can too.
 *
 * <p>Is_Primary is mapped to a field named {@code primary}, not {@code isPrimary} - same reasoning
 * as ProductEntity.active. A field called isPrimary resolves to JavaBean property "primary" anyway,
 * so a derived query written as findByIsPrimaryTrue() would fail at startup.
 */
@Entity
@Table(name = "deal_contacts")
@Getter
@Setter
@NoArgsConstructor
public class DealContactEntity {

    @EmbeddedId
    private DealContactId id;

    /** Column default is FALSE; Java's primitive default agrees, so inserts need no special care. */
    @Column(name = "Is_Primary", nullable = false)
    private boolean primary;
}
