package com.closemore.backend.service;

import com.closemore.backend.domain.ActivityEntity;
import com.closemore.backend.domain.DealEntity;
import com.closemore.backend.domain.UserEntity;
import com.closemore.backend.dto.ActivityResponse;
import com.closemore.backend.dto.ForecastResponse;
import com.closemore.backend.dto.LeaderboardEntryResponse;
import com.closemore.backend.dto.RevenueSummaryResponse;
import com.closemore.backend.dto.StageSummaryResponse;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.repository.ActivityRepository;
import com.closemore.backend.repository.DealRepository;
import com.closemore.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five read-only dashboard aggregates.
 *
 * <p><b>Two scoping layers, and both matter.</b> RLS already restricts every query here to the
 * caller's organisation, so a cross-tenant total is impossible. On top of that, v5 marks four of
 * these "owner-scoped": a Sales_Rep sees their own numbers, an Admin or Executive sees the whole
 * organisation. That second layer is application logic, via {@code RbacService.canViewAll}.
 *
 * <p><b>Why getting the second layer wrong is worse than a crash.</b> Every endpoint here returns
 * a number. A missing owner filter does not throw - it returns a larger, entirely plausible figure,
 * and a rep would have no way to tell their pipeline total from their team's. So each aggregate
 * takes its deal list from one place, {@code visibleDeals()}, rather than filtering at four call
 * sites where one could be forgotten.
 *
 * <p><b>Aggregating in Java rather than in SQL</b> - deliberate for now. A GROUP BY would be
 * faster, but these run over one organisation's deals behind RLS, and a hand-written aggregate
 * query would have to reproduce the owner filter in SQL where it could drift from canViewAll. If a
 * tenant ever grows large enough for this to hurt, the fix is a projection query per endpoint, and
 * the tests here will hold it honest.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    /** v5: "Last 10 activity log entries". */
    private static final int RECENT_ACTIVITY_LIMIT = 10;

    private final DealRepository dealRepository;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;

    /** GET /api/v1/dashboard/revenue */
    public RevenueSummaryResponse revenue() {
        long wonCount = 0;
        double wonValue = 0;
        long lostCount = 0;
        double lostValue = 0;

        for (DealEntity deal : visibleDeals()) {
            if (DealStageRules.STATUS_CLOSED_WON.equals(deal.getStatus())) {
                wonCount++;
                wonValue += deal.getDealValue();
            } else if (DealStageRules.STATUS_CLOSED_LOST.equals(deal.getStatus())) {
                lostCount++;
                lostValue += deal.getDealValue();
            }
        }
        return new RevenueSummaryResponse(wonCount, wonValue, lostCount, lostValue);
    }

    /** GET /api/v1/dashboard/forecast */
    public ForecastResponse forecast() {
        long openCount = 0;
        double openValue = 0;
        double weightedValue = 0;

        for (DealEntity deal : visibleDeals()) {
            if (!DealStageRules.STATUS_OPEN.equals(deal.getStatus())) {
                continue;
            }
            openCount++;
            openValue += deal.getDealValue();
            weightedValue += deal.getDealValue() * deal.getProbabilityPercentage() / 100.0;
        }
        return new ForecastResponse(openCount, openValue, weightedValue);
    }

    /**
     * GET /api/v1/dashboard/pipeline - open deals grouped by stage.
     *
     * <p>Only open deals. A stage breakdown that included closed business would double-count
     * against the revenue endpoint and make the pipeline look permanently full.
     */
    public List<StageSummaryResponse> pipeline() {
        // LinkedHashMap so the response order is stable between calls. A dashboard that reshuffles
        // its bars on every refresh reads as a bug even when the numbers are right.
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, double[]> values = new LinkedHashMap<>();

        for (DealEntity deal : visibleDeals()) {
            if (!DealStageRules.STATUS_OPEN.equals(deal.getStatus())) {
                continue;
            }
            String stage = deal.getCurrentStage();
            counts.computeIfAbsent(stage, k -> new long[1])[0]++;
            values.computeIfAbsent(stage, k -> new double[1])[0] += deal.getDealValue();
        }

        return counts.entrySet().stream()
                .map(e -> new StageSummaryResponse(
                        e.getKey(), e.getValue()[0], values.get(e.getKey())[0]))
                .sorted(Comparator.comparing(StageSummaryResponse::stage))
                .toList();
    }

    /**
     * GET /api/v1/dashboard/leaderboard - reps ranked by won revenue.
     *
     * <p><b>Not owner-scoped, unlike the other four.</b> A leaderboard of one person is not a
     * leaderboard. v5 describes it as a ranking of sales reps, so every rep sees the whole
     * organisation's standings - which is the point of publishing one. Still tenant-bounded by RLS.
     *
     * <p><b>The timeframe filter uses {@code Updated_At}, which is a proxy.</b> There is no
     * Closed_Date column on deals: the schema records when a row last changed, not when it closed.
     * Updated_At moves on any edit, so a deal won in March and edited in May counts as May. This is
     * the least-wrong column available and it is flagged in docs/HANDOFF.md - confirm against the
     * JS implementation before anyone reports off these numbers.
     *
     * @param timeframe {@code month}, {@code quarter}, or null for all time
     */
    public List<LeaderboardEntryResponse> leaderboard(String timeframe) {
        currentUserService.require();
        OffsetDateTime cutoff = cutoffFor(timeframe);

        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, double[]> values = new LinkedHashMap<>();

        for (DealEntity deal : dealRepository.findAll()) {
            if (!DealStageRules.STATUS_CLOSED_WON.equals(deal.getStatus())) {
                continue;
            }
            if (cutoff != null
                    && (deal.getUpdatedAt() == null || deal.getUpdatedAt().isBefore(cutoff))) {
                continue;
            }
            counts.computeIfAbsent(deal.getOwnerId(), k -> new long[1])[0]++;
            values.computeIfAbsent(deal.getOwnerId(), k -> new double[1])[0] += deal.getDealValue();
        }

        Map<String, String> names = ownerNames();

        return counts.entrySet().stream()
                .map(e -> new LeaderboardEntryResponse(
                        e.getKey(),
                        names.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue()[0],
                        values.get(e.getKey())[0]))
                .sorted(Comparator.comparingDouble(LeaderboardEntryResponse::wonValue).reversed()
                        .thenComparing(LeaderboardEntryResponse::ownerId))
                .toList();
    }

    /**
     * GET /api/v1/dashboard/recent-activity - the last 10 entries.
     *
     * <p>Owner-scoped: a rep sees what they logged, an Admin or Executive sees the organisation's.
     * Sorted then truncated in Java rather than with a Pageable, because the owner filter is
     * applied first and a database LIMIT would cut the list before it.
     */
    public List<ActivityResponse> recentActivity() {
        AuthenticatedUser user = currentUserService.require();

        List<ActivityEntity> activities = rbacService.canViewAll(user)
                ? activityRepository.findAll(Sort.by(Sort.Direction.DESC, "logDate"))
                : activityRepository.findByLoggedByUserId(user.userId());

        return activities.stream()
                .sorted(Comparator.comparing(
                        ActivityEntity::getLogDate,
                        Comparator.nullsLast(Comparator.<String>reverseOrder())))
                .limit(RECENT_ACTIVITY_LIMIT)
                .map(DtoMapper::toActivityResponse)
                .toList();
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * The deals this caller's numbers are computed from. ONE place, on purpose - see the class
     * javadoc for why a forgotten owner filter is worse here than an exception.
     */
    private List<DealEntity> visibleDeals() {
        AuthenticatedUser user = currentUserService.require();
        List<DealEntity> all = dealRepository.findAll();

        if (rbacService.canViewAll(user)) {
            return all;
        }
        return all.stream()
                .filter(deal -> user.userId().equals(deal.getOwnerId()))
                .toList();
    }

    private Map<String, String> ownerNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (UserEntity user : userRepository.findAll()) {
            names.put(user.getUserId(), user.getFirstName() + " " + user.getLastName());
        }
        return names;
    }

    private static OffsetDateTime cutoffFor(String timeframe) {
        if (timeframe == null || timeframe.isBlank()) {
            return null;
        }
        return switch (timeframe.toLowerCase(java.util.Locale.ROOT)) {
            case "month" -> OffsetDateTime.now().minusMonths(1);
            case "quarter" -> OffsetDateTime.now().minusMonths(3);
            default -> throw new BadRequestException(
                    "timeframe must be 'month' or 'quarter'");
        };
    }
}
