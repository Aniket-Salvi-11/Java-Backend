package com.closemore.backend.tenant;

import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.LineItemEntity;
import com.closemore.backend.repository.DealRepository;
import com.closemore.backend.repository.LineItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Test-only. Runs deal and line-item reads through the same
 * {@code @Transactional -> TenantContextAspect -> Hibernate} path production code will use.
 * Mirrors ContactReadService and ReferenceDataReadService.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DealsReadService {

    private final DealRepository deals;
    private final LineItemRepository lineItems;

    public List<DealEntity> allDeals() {
        return deals.findAll();
    }

    public Optional<DealEntity> dealById(String id) {
        return deals.findById(id);
    }

    public List<LineItemEntity> allLineItems() {
        return lineItems.findAll();
    }

    public List<LineItemEntity> lineItemsForDeal(String dealId) {
        return lineItems.findByDealId(dealId);
    }
}
