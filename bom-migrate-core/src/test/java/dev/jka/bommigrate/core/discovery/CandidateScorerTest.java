package dev.jka.bommigrate.core.discovery;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateScorerTest {

    private final CandidateScorer scorer = new CandidateScorer();

    @Test
    void scoreIsOneForUniversalConsistent() {
        Map<String, Integer> versions = Map.of("1.0.0", 10);
        double score = scorer.score(10, 10, versions);
        assertThat(score).isBetween(0.95, 1.0);
    }

    @Test
    void scoreIsLowerForPartialCoverage() {
        Map<String, Integer> versions = Map.of("1.0.0", 3);
        double universal = scorer.score(10, 10, Map.of("1.0.0", 10));
        double partial = scorer.score(3, 10, versions);
        assertThat(partial).isLessThan(universal);
    }

    @Test
    void majorConflictScoresLowerThanMinor() {
        Map<String, Integer> minorConflict = new LinkedHashMap<>();
        minorConflict.put("1.0.0", 5);
        minorConflict.put("1.0.1", 5);

        Map<String, Integer> majorConflict = new LinkedHashMap<>();
        majorConflict.put("1.0.0", 5);
        majorConflict.put("2.0.0", 5);

        double minorScore = scorer.score(10, 10, minorConflict);
        double majorScore = scorer.score(10, 10, majorConflict);
        assertThat(majorScore).isLessThan(minorScore);
    }

    @Test
    void assessConflictNoneForSingleVersion() {
        assertThat(scorer.assessConflict(Map.of("1.0.0", 5)))
                .isEqualTo(ConflictSeverity.NONE);
    }

    @Test
    void assessConflictMinorForSameMajor() {
        Map<String, Integer> versions = new LinkedHashMap<>();
        versions.put("1.0.0", 3);
        versions.put("1.2.0", 2);
        assertThat(scorer.assessConflict(versions)).isEqualTo(ConflictSeverity.MINOR);
    }

    @Test
    void assessConflictMajorForDifferentMajors() {
        Map<String, Integer> versions = new LinkedHashMap<>();
        versions.put("1.0.0", 3);
        versions.put("2.0.0", 2);
        assertThat(scorer.assessConflict(versions)).isEqualTo(ConflictSeverity.MAJOR);
    }

    @Test
    void suggestedVersionIsMostFrequent() {
        Map<String, Integer> versions = new LinkedHashMap<>();
        versions.put("1.0.0", 2);
        versions.put("2.0.0", 7);
        versions.put("3.0.0", 1);
        assertThat(scorer.suggestVersion(versions)).isEqualTo("2.0.0");
    }

    @Test
    void scoreZeroForEmpty() {
        assertThat(scorer.score(0, 0, Map.of())).isZero();
    }
}
