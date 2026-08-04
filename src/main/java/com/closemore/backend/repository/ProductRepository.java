package com.closemore.backend.repository;

import com.closemore.backend.domain.ProductEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /**
     * Active-only variants for GET /api/v1/products?activeOnly=true, added in tranche 6.
     *
     * <p>The property is {@code active}, not {@code isActive} - see ProductEntity, where naming the
     * field {@code isActive} would make this derived query fail at startup with
     * "No property 'isActive' found".
     *
     * <p>Sort and Pageable variants rather than one Pageable method: findAll(Pageable) with an
     * unpaged Pageable silently drops the Sort, which is the bug fixed in 89cbc6b.
     */
    List<ProductEntity> findByActive(boolean active, Sort sort);

    Page<ProductEntity> findByActive(boolean active, Pageable pageable);
}
