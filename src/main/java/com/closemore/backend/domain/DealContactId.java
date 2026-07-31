package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Composite primary key for {@link DealContactEntity} - (Deal_ID, Contact_ID).
 *
 * <p>JPA requires an id class to be Serializable and to implement equals/hashCode, because the
 * persistence context keys entities by it. Lombok's @EqualsAndHashCode covers the second
 * requirement; forgetting it produces no compile error and no validation failure - just a first
 * level cache that never hits, and findById returning a fresh row every time.
 *
 * <p>@NoArgsConstructor is mandatory (JPA instantiates it reflectively); @AllArgsConstructor is
 * the convenience used by callers building a lookup key.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class DealContactId implements Serializable {

    @Column(name = "Deal_ID", nullable = false, updatable = false)
    private String dealId;

    @Column(name = "Contact_ID", nullable = false, updatable = false)
    private String contactId;
}
