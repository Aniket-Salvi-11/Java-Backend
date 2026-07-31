package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** Composite primary key for {@link DealTeamMemberEntity} - (Deal_ID, User_ID). */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class DealTeamMemberId implements Serializable {

    @Column(name = "Deal_ID", nullable = false, updatable = false)
    private String dealId;

    @Column(name = "User_ID", nullable = false, updatable = false)
    private String userId;
}
