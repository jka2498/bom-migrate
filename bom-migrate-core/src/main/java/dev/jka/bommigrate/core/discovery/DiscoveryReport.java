package dev.jka.bommigrate.core.discovery;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Result of a dependency discovery scan across multiple service POMs.
 *
 * @param candidates           ranked list of BOM candidates (sorted by score descending)
 * @param totalServicesScanned total number of service POMs scanned
 * @param scanTimestamp        when the scan was run
 */
public record DiscoveryReport(
        List<BomCandidate> candidates,
        int totalServicesScanned,
        Instant scanTimestamp
) {

    public DiscoveryReport {
        candidates = Collections.unmodifiableList(candidates);
    }

    /** Candidates whose versions differ across services. */
    public List<BomCandidate> conflicting() {
        return candidates.stream()
                .filter(c -> c.conflictSeverity() != ConflictSeverity.NONE)
                .toList();
    }

    /** Number of unique groupId:artifactId pairs found. */
    public int uniqueDependencyCount() {
        return candidates.size();
    }

    /** Candidates with a composite score above the given threshold. */
    public List<BomCandidate> candidatesAbove(double scoreThreshold) {
        return candidates.stream()
                .filter(c -> c.score() >= scoreThreshold)
                .toList();
    }
}
