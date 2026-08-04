package com.closemore.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/pipelines - Admin only.
 *
 * <p>{@code stagesJson} is raw JSON text, matching PipelineEntity and PipelineResponse. It is
 * validated as a shape - an array of objects each with a non-blank "name" - but not deserialised
 * into a Java type, because the database does not enforce a schema for it either and inventing one
 * here would put two definitions of a stage in the codebase.
 */
public record PipelineCreateRequest(
        @NotBlank(message = "pipelineName is required") String pipelineName,
        @NotBlank(message = "stagesJson is required") String stagesJson) {
}
