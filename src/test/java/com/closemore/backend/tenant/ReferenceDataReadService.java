package com.closemore.backend.tenant;

import com.closemore.backend.domain.PipelineEntity;
import com.closemore.backend.domain.ProductEntity;
import com.closemore.backend.repository.PipelineRepository;
import com.closemore.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Test-only. Wraps the reference-data repositories in an explicit transaction, mirroring
 * ContactReadService.
 *
 * <p>Calling a Spring Data repository straight from a test would work here - products and pipelines
 * have no RLS - but it would exercise a different path from production code and leave the aspect's
 * interaction with SimpleJpaRepository's own class-level {@code @Transactional} unexercised. Going
 * through a service keeps every JPA read in the suite on the same route.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReferenceDataReadService {

    private final ProductRepository products;
    private final PipelineRepository pipelines;

    public List<ProductEntity> allProducts() {
        return products.findAll();
    }

    public List<ProductEntity> activeProducts() {
        return products.findByActiveTrue();
    }

    public Optional<PipelineEntity> pipelineById(String id) {
        return pipelines.findById(id);
    }

    public List<PipelineEntity> allPipelines() {
        return pipelines.findAll();
    }
}
