package com.closemore.backend.repository;

import com.closemore.backend.domain.LineItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for deal line items.
 *
 * <p>Note there is no owner-scoped finder here and there cannot be one: line_items has no owner or
 * tenant column. Access is decided entirely by the parent deal, two joins away - see
 * LineItemEntity. findByDealId is therefore the natural query, and RLS independently guarantees it
 * returns nothing for a deal the caller cannot see.
 */
public interface LineItemRepository extends JpaRepository<LineItemEntity, String> {

    List<LineItemEntity> findByDealId(String dealId);
}
