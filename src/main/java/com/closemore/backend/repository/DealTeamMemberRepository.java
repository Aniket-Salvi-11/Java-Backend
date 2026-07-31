package com.closemore.backend.repository;

import com.closemore.backend.domain.DealTeamMemberEntity;
import com.closemore.backend.domain.DealTeamMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data JPA repository for deal team membership. See DealContactRepository on the id path. */
public interface DealTeamMemberRepository
        extends JpaRepository<DealTeamMemberEntity, DealTeamMemberId> {

    List<DealTeamMemberEntity> findById_DealId(String dealId);

    List<DealTeamMemberEntity> findById_UserId(String userId);
}
