package com.closemore.backend.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps the `deal_team_members` join table from V9__deal_teams.sql.
 *
 * <p>The whole row is its primary key - (Deal_ID, User_ID) and nothing else - so this entity is
 * just an @EmbeddedId. That is legal JPA and not a mistake.
 *
 * <p><b>This table is load-bearing for access control, not just data.</b> V9 rewrote the deals,
 * line_items and deal_contacts policies so that membership here grants read access to the parent
 * deal and everything hanging off it. Inserting a row is therefore a privilege grant, and the
 * WITH CHECK clause on this table is what stops one tenant adding themselves to another's deal.
 *
 * <p>Note its own policy is deliberately weaker than the others: it checks only that the MEMBER
 * belongs to the current tenant, with no owner or role condition. So any user can see team
 * membership rows for colleagues in their organisation, including for deals they cannot open. That
 * is a real (if minor) disclosure of who works on what - flagged rather than changed, because it
 * matches the JS original and is a product decision, not a bug to fix mid-port.
 */
@Entity
@Table(name = "deal_team_members")
@Getter
@Setter
@NoArgsConstructor
public class DealTeamMemberEntity {

    @EmbeddedId
    private DealTeamMemberId id;
}
