package com.closemore.backend.repository;

import com.closemore.backend.domain.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for the product catalogue.
 *
 * <p>Unlike ContactRepository and UserRepository, this one is NOT behind RLS - products are global
 * reference data with no policy. findAll() genuinely returns every product regardless of tenant or
 * role, and that is correct rather than a leak. Do not copy this comment onto a tenant-scoped
 * repository.
 *
 * <p>{@code findByActiveTrue} matches the entity's {@code active} field - see ProductEntity's note
 * on why it is not named {@code isActive}.
 */
public interface ProductRepository extends JpaRepository<ProductEntity, String> {

    List<ProductEntity> findByActiveTrue();
}
