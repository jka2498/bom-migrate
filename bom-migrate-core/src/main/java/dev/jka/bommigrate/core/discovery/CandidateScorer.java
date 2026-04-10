package dev.jka.bommigrate.core.discovery;

import java.util.Map;

/**
 * Pure scoring logic for ranking BOM candidates.
 *
 * <p>Score formula:
 * <pre>
 *   score = 0.5 * frequencyRatio + 0.3 * versionConsistency + 0.2 * (1 - conflictPenalty)
 * </pre>
 * All components normalized to [0, 1].
 */
public final class CandidateScorer {

    /**
     * Computes a composite score for a candidate.
     *
     * @param serviceCount   number of services using the dependency
     * @param totalServices  total number of services scanned
     * @param versions       version → count map across services
     * @return score in [0.0, 1.0]
     */
    public double score(int serviceCount, int totalServices, Map<String, Integer> versions) {
        if (totalServices == 0 || versions == null || versions.isEmpty()) {
            return 0.0;
        }

        double frequencyRatio = (double) serviceCount / totalServices;
        double versionConsistency = versionConsistency(versions, serviceCount);
        double conflictPenalty = conflictPenalty(assessConflict(versions));

        return 0.5 * frequencyRatio + 0.3 * versionConsistency + 0.2 * (1.0 - conflictPenalty);
    }

    /**
     * Determines conflict severity from version distribution.
     * NONE: all services use the same version
     * MINOR: different minor/patch versions
     * MAJOR: different major versions (biggest segment before first dot)
     */
    public ConflictSeverity assessConflict(Map<String, Integer> versions) {
        if (versions == null || versions.size() <= 1) {
            return ConflictSeverity.NONE;
        }

        // Extract major versions (first numeric segment)
        long distinctMajors = versions.keySet().stream()
                .map(this::extractMajor)
                .distinct()
                .count();

        if (distinctMajors > 1) {
            return ConflictSeverity.MAJOR;
        }
        return ConflictSeverity.MINOR;
    }

    /**
     * Returns the dominant version (most services using it) as the suggested
     * version for the BOM. Ties broken by lexicographic ordering.
     */
    public String suggestVersion(Map<String, Integer> versions) {
        return versions.entrySet().stream()
                .max((a, b) -> {
                    int cmp = Integer.compare(a.getValue(), b.getValue());
                    if (cmp != 0) return cmp;
                    return a.getKey().compareTo(b.getKey());
                })
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private double versionConsistency(Map<String, Integer> versions, int serviceCount) {
        if (serviceCount == 0) return 0.0;
        int dominantCount = versions.values().stream().max(Integer::compareTo).orElse(0);
        return (double) dominantCount / serviceCount;
    }

    private double conflictPenalty(ConflictSeverity severity) {
        return switch (severity) {
            case NONE -> 0.0;
            case MINOR -> 0.3;
            case MAJOR -> 1.0;
        };
    }

    private String extractMajor(String version) {
        if (version == null || version.isEmpty()) return "";
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }
}
