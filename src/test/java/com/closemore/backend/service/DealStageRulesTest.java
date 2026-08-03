package com.closemore.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the one place that decides what a stage name means.
 *
 * <p>Unit rather than integration on purpose: this is pure string logic with no database in it, and
 * the whole reason the class exists is that the same comparison was written three times in the
 * Next.js codebase with slightly different implementations. Cheap tests here are what stop a fourth
 * variant appearing the next time someone needs the answer.
 *
 * <p>The failure this guards against is silent. A stage comparison that is too strict does not throw
 * - the deal moves, the screen updates, and the revenue simply never counts in any report that
 * groups by status.
 */
class DealStageRulesTest {

    @Test
    void theCanonicalSpellingIsRecognised() {
        assertThat(DealStageRules.isClosedWon("Closed Won")).isTrue();
        assertThat(DealStageRules.isClosedLost("Closed Lost")).isTrue();
    }

    @Test
    void casingAndPunctuationDoNotChangeTheMeaning() {
        // Stage names are free text typed by an administrator when configuring a pipeline. All of
        // these are the same stage as far as any human is concerned.
        assertThat(DealStageRules.isClosedWon("closed won")).isTrue();
        assertThat(DealStageRules.isClosedWon("CLOSED-WON")).isTrue();
        assertThat(DealStageRules.isClosedWon("Closed_Won")).isTrue();
        assertThat(DealStageRules.isClosedWon("  Closed   Won  ")).isTrue();
    }

    @Test
    void unrelatedStagesAreNotTerminal() {
        assertThat(DealStageRules.isTerminal("Prospecting")).isFalse();
        assertThat(DealStageRules.isTerminal("Negotiation")).isFalse();
        assertThat(DealStageRules.isTerminal("Proposal Sent")).isFalse();
    }

    @Test
    void wonAndLostAreNotConfusedWithEachOther() {
        assertThat(DealStageRules.isClosedWon("Closed Lost")).isFalse();
        assertThat(DealStageRules.isClosedLost("Closed Won")).isFalse();
    }

    @Test
    void aNullOrEmptyStageIsSimplyNotTerminal() {
        // Reached through a code path that never validated the stage. Returning false is the safe
        // answer: an unrecognised stage leaves the deal open rather than silently booking revenue.
        assertThat(DealStageRules.isTerminal(null)).isFalse();
        assertThat(DealStageRules.isTerminal("")).isFalse();
    }

    @Test
    void statusFollowsTheStage() {
        assertThat(DealStageRules.statusForStage("Closed Won"))
                .isEqualTo(DealStageRules.STATUS_CLOSED_WON);
        assertThat(DealStageRules.statusForStage("Closed Lost"))
                .isEqualTo(DealStageRules.STATUS_CLOSED_LOST);
        assertThat(DealStageRules.statusForStage("Negotiation"))
                .isEqualTo(DealStageRules.STATUS_OPEN);
    }

    @Test
    void probabilityIsForcedOnlyForTerminalStages() {
        assertThat(DealStageRules.probabilityForStage("Closed Won")).isEqualTo(100);
        assertThat(DealStageRules.probabilityForStage("Closed Lost")).isEqualTo(0);
        // Null means "no opinion" rather than a default the caller would have to recognise -
        // a non-terminal stage keeps whatever probability the user set.
        assertThat(DealStageRules.probabilityForStage("Prospecting")).isNull();
    }
}
