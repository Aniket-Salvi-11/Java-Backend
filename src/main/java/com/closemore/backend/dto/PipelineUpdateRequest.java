package com.closemore.backend.dto;

/**
 * PUT /api/v1/pipelines/{pipelineId} - Admin only. Null means "leave alone".
 *
 * <p>Supplying {@code stagesJson} can move deals: v5 says this route "reassigns deals on stage
 * rename/removal". See PipelineService.update for exactly which deals move and which deliberately
 * do not.
 */
public record PipelineUpdateRequest(
        String pipelineName,
        String stagesJson) {
}
