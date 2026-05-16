package dev.jka.bommigrate.core.discovery;

import dev.jka.bommigrate.core.model.PomModelReader;
import dev.jka.bommigrate.core.resolver.PropertyInterpolator;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;

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
        return analyse(pomPaths, minFrequency, false);
    }

    /**
     * Analyses the given POM files, optionally including plugin discovery.
     *
     * @param includePlugins if true, also scans {@code <build><plugins>} for plugin candidates
     */
    public DiscoveryReport analyse(List<Path> pomPaths, int minFrequency, boolean includePlugins) throws IOException {
        Map<String, Map<String, Integer>> depFrequencyMap = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> pluginFrequencyMap = new LinkedHashMap<>();
        int servicesScanned = 0;

        for (Path pomPath : pomPaths) {
            Model model;
            try {
                model = PomModelReader.parseModel(pomPath);
            } catch (IOException e) {
                System.err.println("[WARN] Skipping unparseable POM: " + pomPath + " (" + e.getMessage() + ")");
                continue;
            }
            servicesScanned++;
            ingestDependencies(model, depFrequencyMap);
            if (includePlugins) {
                ingestPlugins(model, pluginFrequencyMap);
            }
        }

        List<BomCandidate> candidates = buildCandidates(depFrequencyMap, servicesScanned, minFrequency);
        List<BomCandidate> pluginCandidates = includePlugins
                ? buildCandidates(pluginFrequencyMap, servicesScanned, minFrequency)
                : List.of();

        return new DiscoveryReport(candidates, pluginCandidates, servicesScanned, Instant.now());
    }

    private List<BomCandidate> buildCandidates(Map<String, Map<String, Integer>> frequencyMap,
                                               int servicesScanned, int minFrequency) {
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
                    serviceCount, servicesScanned,
                    suggested, severity, score
            ));
        }

        candidates.sort(Comparator
                .comparingDouble(BomCandidate::score).reversed()
                .thenComparing((BomCandidate c) -> c.conflictSeverity().ordinal())
                .thenComparing(BomCandidate::key));
        return candidates;
    }

    private void ingestDependencies(Model model, Map<String, Map<String, Integer>> frequencyMap) {
        List<Dependency> dependencies = model.getDependencies();
        if (dependencies == null || dependencies.isEmpty()) {
            return;
        }

        PropertyInterpolator interpolator = PomModelReader.buildInterpolator(model);
        Set<String> seenInThisPom = new HashSet<>();

        for (Dependency dep : dependencies) {
            if (dep.getGroupId() == null || dep.getArtifactId() == null) {
                continue;
            }
            String key = dep.getGroupId() + ":" + dep.getArtifactId();
            if (!seenInThisPom.add(key)) {
                continue;
            }
            String rawVersion = dep.getVersion();
            if (rawVersion == null || rawVersion.isBlank()) {
                continue;
            }
            String resolvedVersion = interpolator.interpolate(rawVersion);
            if (resolvedVersion == null || resolvedVersion.contains("${")) {
                continue;
            }
            frequencyMap
                    .computeIfAbsent(key, k -> new HashMap<>())
                    .merge(resolvedVersion, 1, Integer::sum);
        }
    }

    private void ingestPlugins(Model model, Map<String, Map<String, Integer>> frequencyMap) {
        if (model.getBuild() == null || model.getBuild().getPlugins() == null) {
            return;
        }

        PropertyInterpolator interpolator = PomModelReader.buildInterpolator(model);
        Set<String> seenInThisPom = new HashSet<>();

        for (Plugin plugin : model.getBuild().getPlugins()) {
            String groupId = plugin.getGroupId() != null
                    ? plugin.getGroupId() : "org.apache.maven.plugins";
            String artifactId = plugin.getArtifactId();
            if (artifactId == null) {
                continue;
            }
            String key = groupId + ":" + artifactId;
            if (!seenInThisPom.add(key)) {
                continue;
            }
            String rawVersion = plugin.getVersion();
            if (rawVersion == null || rawVersion.isBlank()) {
                continue;
            }
            String resolvedVersion = interpolator.interpolate(rawVersion);
            if (resolvedVersion == null || resolvedVersion.contains("${")) {
                continue;
            }
            frequencyMap
                    .computeIfAbsent(key, k -> new HashMap<>())
                    .merge(resolvedVersion, 1, Integer::sum);
        }
    }

    /**
     * Legacy ingest method — retained for backward compat with the 1-arg analyse().
     */
    private boolean ingestPom(Path pomPath, Map<String, Map<String, Integer>> frequencyMap) throws IOException {
        Model model;
        try {
            model = PomModelReader.parseModel(pomPath);
        } catch (IOException e) {
            System.err.println("[WARN] Skipping unparseable POM: " + pomPath + " (" + e.getMessage() + ")");
            return false;
        }
        ingestDependencies(model, frequencyMap);
        return true;
    }
}
