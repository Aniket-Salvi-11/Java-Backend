package com.closemore.backend.service;

import java.util.Locale;

/**
 * The one place that decides what a stage name means.
 *
 * <p><b>This class exists because of a named risk, not because the logic is hard.</b> Section 11 of
 * the migration plan records that stage-name pattern matching for "Closed Won" detection appears
 * independently in at least three places in the Next.js codebase, with slightly different
 * implementations, and recommends consolidating during the port rather than reproducing the
 * duplication a fourth time. Three copies of a string comparison drift: one trims, one lowercases,
 * one does neither, and a deal recorded as won on the deal screen is still open on the dashboard.
 *
 * <p>Everything that needs to know whether a stage is terminal calls this - the stage-change
 * endpoint, the lost endpoint, and any future dashboard aggregation. If the product later renames a
 * stage or adds a second winning stage, this file is the only edit.
 *
 * <p><b>Matching is deliberately forgiving.</b> Stage names are free text configured per pipeline,
 * typed by an administrator. "Closed Won", "closed won" and "Closed-Won" are the same stage as far
 * as any human is concerned, so they are the same stage here. The alternative - exact matching -
 * fails silently: the deal moves, no error appears, and the revenue simply never counts.
 */
public final class DealStageRules {

    /** Status values written to deals."Status". Distinct from the stage name. */
    public static final String STATUS_OPEN = "Open";
    public static final String STATUS_CLOSED_WON = "Closed Won";
    public static final String STATUS_CLOSED_LOST = "Closed Lost";

    private static final String WON = "closedwon";
    private static final String LOST = "closedlost";

    private DealStageRules() {
    }

    public static boolean isClosedWon(String stageName) {
        return WON.equals(normalise(stageName));
    }

    public static boolean isClosedLost(String stageName) {
        return LOST.equals(normalise(stageName));
    }

    /** True for any terminal stage - a deal here is no longer forecastable. */
    public static boolean isTerminal(String stageName) {
        return isClosedWon(stageName) || isClosedLost(stageName);
    }

    /**
     * The Status a deal should carry given the stage it now sits in.
     *
     * <p>Status and Current_Stage are separate columns and both are written, because they answer
     * different questions: the stage is where the deal is in a specific pipeline's sequence, the
     * status is the coarse open/won/lost bucket every report groups by. Deriving one from the other
     * at read time would mean every consumer reimplementing this method - which is exactly the
     * problem this class was created to end.
     */
    public static String statusForStage(String stageName) {
        if (isClosedWon(stageName)) {
            return STATUS_CLOSED_WON;
        }
        if (isClosedLost(stageName)) {
            return STATUS_CLOSED_LOST;
        }
        return STATUS_OPEN;
    }

    /**
     * The probability a terminal stage implies. A won deal is certain and a lost deal is impossible;
     * anything else keeps whatever the user set, which is why this returns null rather than a
     * default the caller would have to recognise as "no opinion".
     */
    public static Integer probabilityForStage(String stageName) {
        if (isClosedWon(stageName)) {
            return 100;
        }
        if (isClosedLost(stageName)) {
            return 0;
        }
        return null;
    }

    /** Lowercase, and strip everything that is not a letter - so spaces, hyphens and underscores all fall away. */
    private static String normalise(String stageName) {
        if (stageName == null) {
            return "";
        }
        return stageName.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
