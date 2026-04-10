package dev.jka.bommigrate.core.discovery;

import java.util.Collections;
import java.util.Map;

/**
 * A dependency that is a candidate for inclusion in a generated BOM.
 * Ranked by frequency across services and version consistency.
 *
 * @param groupId                Maven groupId
 * @param artifactId             Maven artifactId
 * @param versions               map from version string to count of services using that version
 * @param serviceCount           total services using this dependency
 * @param totalServicesScanned   total number of services scanned overall
 * @param suggestedVersion       the version to include in the generated BOM (most frequent)
 * @param conflictSeverity       severity of cross-service version divergence
 * @param score                  composite ranking score in [0.0, 1.0] (higher = stronger candidate)
 */
public record BomCandidate(
        String groupId,
        String artifactId,
        Map<String, Integer> versions,
        int serviceCount,
        int totalServicesScanned,
        String suggestedVersion,
        ConflictSeverity conflictSeverity,
        double score
) {

    public BomCandidate {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        versions = Collections.unmodifiableMap(versions);
    }

    /** Canonical lookup key: "groupId:artifactId". */
    public String key() {
        return groupId + ":" + artifactId;
    }

    /** Fraction of scanned services that declare this dependency. */
    public double frequencyRatio() {
        if (totalServicesScanned == 0) {
            return 0.0;
        }
        return (double) serviceCount / totalServicesScanned;
    }
}
