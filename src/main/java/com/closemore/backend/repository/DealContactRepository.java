package com.closemore.backend.repository;

import com.closemore.backend.domain.DealContactEntity;
import com.closemore.backend.domain.DealContactId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for the deal/contact join table.
 *
 * <p>The ID type is the composite key class, so findById takes a {@code new DealContactId(dealId,
 * contactId)}.
 *
 * <p>Derived queries reach into the embedded key with an underscore: {@code findById_DealId} means
 * the property path {@code id.dealId}. The underscore is the documented disambiguator - without it
 * Spring Data has to guess where the property boundary falls, and guesses wrong on names like this.
 */
public interface DealContactRepository extends JpaRepository<DealContactEntity, DealContactId> {

    List<DealContactEntity> findById_DealId(String dealId);
}
