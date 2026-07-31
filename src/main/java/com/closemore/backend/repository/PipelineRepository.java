package com.closemore.backend.repository;

import com.closemore.backend.domain.PipelineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for pipelines. Global reference data, no RLS - see ProductRepository.
 */
public interface PipelineRepository extends JpaRepository<PipelineEntity, String> {
}
