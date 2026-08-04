package com.closemore.backend.controller;

import com.closemore.backend.dto.PipelineCreateRequest;
import com.closemore.backend.dto.PipelineResponse;
import com.closemore.backend.dto.PipelineUpdateRequest;
import com.closemore.backend.service.PipelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pipelines and their stage configuration. Reads open to any authenticated user, writes Admin only.
 *
 * <p>Never paginated - see PipelineService.listAll for why a partially-delivered stage list is
 * worse than a large response.
 */
@RestController
@RequestMapping("/api/v1/pipelines")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    /** GET /api/v1/pipelines */
    @GetMapping
    public List<PipelineResponse> list() {
        return pipelineService.listAll();
    }

    /** GET /api/v1/pipelines/{pipelineId} */
    @GetMapping("/{pipelineId}")
    public PipelineResponse get(@PathVariable String pipelineId) {
        return pipelineService.get(pipelineId);
    }

    /** POST /api/v1/pipelines - Admin only. */
    @PostMapping
    public ResponseEntity<PipelineResponse> create(
            @Valid @RequestBody PipelineCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pipelineService.create(request));
    }

    /**
     * PUT /api/v1/pipelines/{pipelineId} - Admin only.
     *
     * <p>Can move deals. See PipelineService.update for which ones move, which deliberately do not,
     * and why the reassignment stops at the caller's tenant boundary.
     */
    @PutMapping("/{pipelineId}")
    public PipelineResponse update(@PathVariable String pipelineId,
                                   @Valid @RequestBody PipelineUpdateRequest request) {
        return pipelineService.update(pipelineId, request);
    }

    /** DELETE /api/v1/pipelines/{pipelineId} - Admin only. 409 if any deal still uses it. */
    @DeleteMapping("/{pipelineId}")
    public ResponseEntity<Void> delete(@PathVariable String pipelineId) {
        pipelineService.delete(pipelineId);
        return ResponseEntity.noContent().build();
    }
}
