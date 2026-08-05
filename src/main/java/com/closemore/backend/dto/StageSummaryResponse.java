package com.closemore.backend.dto;

/**
 * One row of GET /api/v1/dashboard/pipeline - open deals grouped by stage.
 *
 * <p>Stage is a name rather than an id, matching {@code Current_Stage} on deals. A deal stranded on
 * a stage that no longer exists in its pipeline still appears here under its stored name - see
 * PipelineService.update for how that happens and why it is allowed to.
 */
public record StageSummaryResponse(
        String stage,
        long dealCount,
        double totalValue) {
}
