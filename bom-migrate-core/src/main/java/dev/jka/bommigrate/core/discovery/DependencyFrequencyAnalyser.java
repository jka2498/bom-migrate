package dev.jka.bommigrate.core.discovery;

import dev.jka.bommigrate.core.model.PomModelReader;
import dev.jka.bommigrate.core.resolver.PropertyInterpolator;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans multiple service POMs and builds a frequency/version map for each
 * {@code groupId:artifactId} encountered. Produces a ranked {@link DiscoveryReport}
 * of BOM candidates.
 *
 * <p>Streaming implementation: parses one POM at a time, extracts dependencies
 * into the aggregate frequency map, and discards the parsed model. Never holds
 * all parsed models in memory at once.
 */
public final class DependencyFrequencyAnalyser {

    private final CandidateScorer scorer = new CandidateScorer();

    /**
     * Analyses the given POM files and produces a discovery report.
     *
     * @param pomPaths paths to service {@code pom.xml} files
     * @return report with ranked BOM candidates
     * @throws IOException on parse errors
     */
    public DiscoveryReport analyse(List<Path> pomPaths) throws IOException {
        return analyse(pomPaths, 1);
    }

    /**
     * Analyses the given POM files and produces a discovery report, filtering
     * out candidates that appear in fewer than {@code minFrequency} services.
     */
    public DiscoveryReport analyse(List<Path> pomPaths, int minFrequency) throws IOException {
        // aggregate map: "groupId:artifactId" → version → count
        Map<String, Map<String, Integer>> frequencyMap = new LinkedHashMap<>();
        int servicesScanned = 0;

        for (Path pomPath : pomPaths) {
            if (ingestPom(pomPath, frequencyMap)) {
                servicesScanned++;
            }
        }

        List<BomCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : frequencyMap.entrySet()) {
            String key = entry.getKey();
            Map<String, Integer> versions = entry.getValue();
            int serviceCount = versions.values().stream().mapToInt(Integer::intValue).sum();

            if (serviceCount < minFrequency) {
                continue;
            }

            String[] parts = key.split(":", 2);
            String groupId = parts[0];
            String artifactId = parts.length > 1 ? parts[1] : "";

            double score = scorer.score(serviceCount, servicesScanned, versions);
            ConflictSeverity severity = scorer.assessConflict(versions);
            String suggested = scorer.suggestVersion(versions);

            candidates.add(new BomCandidate(
                    groupId, artifactId,
                    new LinkedHashMap<>(versions),
                    serviceCount,
                    servicesScanned,
                    suggested,
                    severity,
                    score
            ));
        }

        // Sort: highest score first, then MAJOR conflicts first, then alphabetical
        candidates.sort(Comparator
                .comparingDouble(BomCandidate::score).reversed()
                .thenComparing((BomCandidate c) -> c.conflictSeverity().ordinal())
                .thenComparing(BomCandidate::key));

        return new DiscoveryReport(candidates, servicesScanned, Instant.now());
    }

    /**
     * Ingests a single POM into the frequency map. Returns true if the POM was
     * successfully parsed and contributed dependencies (counted as one "service").
     */
    private boolean ingestPom(Path pomPath, Map<String, Map<String, Integer>> frequencyMap) throws IOException {
        Model model;
        try {
            model = PomModelReader.parseModel(pomPath);
        } catch (IOException e) {
            System.err.println("[WARN] Skipping unparseable POM: " + pomPath + " (" + e.getMessage() + ")");
            return false;
        }

        List<Dependency> dependencies = model.getDependencies();
        if (dependencies == null || dependencies.isEmpty()) {
            return true; // still a valid service, just no deps
        }

        PropertyInterpolator interpolator = PomModelReader.buildInterpolator(model);

        // Dedupe within this POM to avoid double-counting the same GA in <dependencies>
        Set<String> seenInThisPom = new HashSet<>();

        for (Dependency dep : dependencies) {
            if (dep.getGroupId() == null || dep.getArtifactId() == null) {
                continue;
            }
            String key = dep.getGroupId() + ":" + dep.getArtifactId();
            if (!seenInThisPom.add(key)) {
                continue; // already counted this GA in this POM
            }

            String rawVersion = dep.getVersion();
            if (rawVersion == null || rawVersion.isBlank()) {
                continue; // already managed — not a candidate
            }

            String resolvedVersion = interpolator.interpolate(rawVersion);
            if (resolvedVersion == null || resolvedVersion.contains("${")) {
                continue; // unresolvable — skip
            }

            frequencyMap
                    .computeIfAbsent(key, k -> new HashMap<>())
                    .merge(resolvedVersion, 1, Integer::sum);
        }
        return true;
    }
}
