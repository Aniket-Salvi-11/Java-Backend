package com.closemore.backend.tenant;

import com.closemore.backend.domain.DealContactEntity;
import com.closemore.backend.domain.DealContactId;
import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.DealTeamMemberEntity;
import com.closemore.backend.repository.DealContactRepository;
import com.closemore.backend.repository.DealRepository;
import com.closemore.backend.repository.DealTeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Test-only. Mirrors DealsReadService; see that class for why reads go through a service. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DealTeamReadService {

    private final DealContactRepository dealContacts;
    private final DealTeamMemberRepository teamMembers;
    private final DealRepository deals;

    public List<DealContactEntity> allDealContacts() {
        return dealContacts.findAll();
    }

    public Optional<DealContactEntity> dealContactById(String dealId, String contactId) {
        return dealContacts.findById(new DealContactId(dealId, contactId));
    }

    public List<DealContactEntity> dealContactsFor(String dealId) {
        return dealContacts.findById_DealId(dealId);
    }

    public List<DealTeamMemberEntity> allTeamMembers() {
        return teamMembers.findAll();
    }

    public List<DealTeamMemberEntity> teamMembersFor(String dealId) {
        return teamMembers.findById_DealId(dealId);
    }

    public List<DealEntity> allDeals() {
        return deals.findAll();
    }
}
