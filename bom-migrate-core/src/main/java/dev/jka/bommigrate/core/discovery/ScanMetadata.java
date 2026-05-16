package dev.jka.bommigrate.core.discovery;

import java.util.Collections;
import java.util.List;

/**
 * Metadata describing where the scanned POMs came from, for display in the
 * CLI and web UI. Distinct from {@link DiscoveryReport} which is pure
 * analyser output.
 *
 * <p>For local scans, {@link #scannedSources} holds POM file paths.
 * For GitHub org scans, entries look like {@code "repo-name: path/to/pom.xml"}
 * and the skip/failure lists may be populated.
 *
 * @param scanSource          human-readable label for where the scan came from
 *                            (e.g. "GitHub org: my-org" or "Local files") — may be null for legacy callers
 * @param scannedSources      human-readable labels for each POM that was analysed
 * @param skippedByLanguage   repo names filtered out by the JVM language pre-filter
 * @param skippedNoPom        repos that cloned successfully but contained no pom.xml
 * @param failedClones        repos that failed to clone, with reason
 */
public record ScanMetadata(
        String scanSource,
        List<String> scannedSources,
        List<String> skippedByLanguage,
        List<String> skippedNoPom,
        List<FailedClone> failedClones
) {

    public ScanMetadata {
        scannedSources = Collections.unmodifiableList(scannedSources);
        skippedByLanguage = Collections.unmodifiableList(skippedByLanguage);
        skippedNoPom = Collections.unmodifiableList(skippedNoPom);
        failedClones = Collections.unmodifiableList(failedClones);
    }

    public record FailedClone(String repoName, String reason) {}

    public static ScanMetadata empty() {
        return new ScanMetadata(null, List.of(), List.of(), List.of(), List.of());
    }

    public static ScanMetadata localOnly(List<String> scannedSources) {
        return new ScanMetadata("Local files", scannedSources, List.of(), List.of(), List.of());
    }

    public static ScanMetadata uploaded(List<String> scannedSources) {
        return new ScanMetadata("Uploaded files", scannedSources, List.of(), List.of(), List.of());
    }

    public static ScanMetadata fromOrg(String orgName, List<String> scannedSources,
                                        List<String> skippedByLanguage, List<String> skippedNoPom,
                                        List<FailedClone> failedClones) {
        return new ScanMetadata("GitHub org: " + orgName, scannedSources,
                skippedByLanguage, skippedNoPom, failedClones);
    }
}
