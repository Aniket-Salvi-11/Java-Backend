package com.closemore.backend.controller;

import com.closemore.backend.dto.ActivityResponse;
import com.closemore.backend.dto.ForecastResponse;
import com.closemore.backend.dto.LeaderboardEntryResponse;
import com.closemore.backend.dto.RevenueSummaryResponse;
import com.closemore.backend.dto.StageSummaryResponse;
import com.closemore.backend.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Five read-only aggregates. No writes, no pagination - each response is either a small object or a
 * list bounded by the number of stages, reps, or ten activities.
 *
 * <p>Four of the five are owner-scoped. The leaderboard is Admin/Executive only - not by choice
 * but because RLS makes a rep's view of it meaningless. See DashboardService.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** GET /api/v1/dashboard/revenue - closed-won against closed-lost. */
    @GetMapping("/revenue")
    public RevenueSummaryResponse revenue() {
        return dashboardService.revenue();
    }

    /** GET /api/v1/dashboard/forecast - open pipeline, raw and probability-weighted. */
    @GetMapping("/forecast")
    public ForecastResponse forecast() {
        return dashboardService.forecast();
    }

    /** GET /api/v1/dashboard/pipeline - open deal count and value by stage. */
    @GetMapping("/pipeline")
    public List<StageSummaryResponse> pipeline() {
        return dashboardService.pipeline();
    }

    /**
     * GET /api/v1/dashboard/leaderboard
     *
     * <p>{@code timeframe} accepts {@code month} or {@code quarter}; omitted means all time. An
     * unrecognised value is a 400 rather than a silent fallback to all time - a caller asking for
     * "week" and receiving every deal ever closed would have no way to notice.
     */
    @GetMapping("/leaderboard")
    public List<LeaderboardEntryResponse> leaderboard(
            @RequestParam(name = "timeframe", required = false) String timeframe) {
        return dashboardService.leaderboard(timeframe);
    }

    /** GET /api/v1/dashboard/recent-activity - the last 10 entries. */
    @GetMapping("/recent-activity")
    public List<ActivityResponse> recentActivity() {
        return dashboardService.recentActivity();
    }
}
