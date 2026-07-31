package com.closemore.backend.dto;

/**
 * Outbound DTO for a pipeline.
 *
 * <p>stagesJson is passed through as a raw JSON string, matching the entity. Jackson will emit it
 * as a quoted string rather than a nested object - if the frontend needs a real array here, that is
 * a deliberate change to make at the DTO layer, not something to fix by accident in the entity.
 */
public record PipelineResponse(
        String pipelineId,
        String pipelineName,
        String stagesJson
) {
}
