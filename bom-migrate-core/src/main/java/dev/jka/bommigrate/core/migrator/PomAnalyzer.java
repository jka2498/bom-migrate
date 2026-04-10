package dev.jka.bommigrate.core.migrator;

import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationAction;
import dev.jka.bommigrate.core.model.MigrationCandidate;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.model.ResolvedDependency;
import dev.jka.bommigrate.core.resolver.PropertyInterpolator;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Analyzes a target microservice POM against a BOM's dependency management map,
 * classifying each versioned dependency as STRIP, FLAG, or SKIP.
 */
public final class PomAnalyzer {

    private static final Pattern VERSION_RANGE_PATTERN = Pattern.compile("[\\[\\]()]");

    /**
     * Analyzes the target POM and produces a migration report.
     *
     * @param targetPomPath path to the microservice pom.xml
     * @param bomMap        the resolved BOM dependency management map
     * @return migration report with classified candidates
     * @throws IOException on parse or file errors
     */
    public MigrationReport analyze(Path targetPomPath, DependencyManagementMap bomMap) throws IOException {
        Model model = parseModel(targetPomPath);
        PropertyInterpolator interpolator = buildInterpolator(model);

        List<Dependency> dependencies = model.getDependencies();
        if (dependencies == null) {
            return new MigrationReport(targetPomPath, List.of());
        }

        // Detect duplicates: track keys we've seen
        Map<String, List<Integer>> keyToIndices = new LinkedHashMap<>();
        List<ResolvedDependency> resolvedDeps = new ArrayList<>();
        List<String> rawVersions = new ArrayList<>();

        for (int i = 0; i < dependencies.size(); i++) {
            Dependency dep = dependencies.get(i);
            String resolvedVersion = resolveVersion(dep, interpolator);
            rawVersions.add(dep.getVersion());

            ResolvedDependency resolved = ResolvedDependency.fromMavenDependency(dep, resolvedVersion);
            resolvedDeps.add(resolved);

            keyToIndices.computeIfAbsent(resolved.key(), k -> new ArrayList<>()).add(i);
        }

        // Find duplicate keys
        Set<Integer> duplicateIndices = new HashSet<>();
        for (List<Integer> indices : keyToIndices.values()) {
            if (indices.size() > 1) {
                duplicateIndices.addAll(indices);
            }
        }

        List<MigrationCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < resolvedDeps.size(); i++) {
            ResolvedDependency resolved = resolvedDeps.get(i);
            String rawVersion = rawVersions.get(i);

            candidates.add(classify(resolved, rawVersion, bomMap, duplicateIndices.contains(i)));
        }

        return new MigrationReport(targetPomPath, candidates);
    }

    private MigrationCandidate classify(
            ResolvedDependency resolved,
            String rawVersion,
            DependencyManagementMap bomMap,
            boolean isDuplicate) {

        // No version tag present — already managed
        if (rawVersion == null || rawVersion.isBlank()) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "already managed (no version tag)", null);
        }

        // Duplicate declaration
        if (isDuplicate) {
            return new MigrationCandidate(resolved, MigrationAction.FLAG,
                    "duplicate declaration", null);
        }

        // Version range
        if (VERSION_RANGE_PATTERN.matcher(resolved.version()).find()) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "version range detected", null);
        }

        // Unresolvable property
        if (resolved.version().contains("${")) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "unresolvable property in version: " + resolved.version(), null);
        }

        // Look up in BOM
        Optional<ResolvedDependency> bomEntry = bomMap.lookup(resolved.key());
        if (bomEntry.isEmpty()) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "not managed by BOM", null);
        }

        ResolvedDependency bomDep = bomEntry.get();

        // Compare versions
        if (resolved.version().equals(bomDep.version())) {
            return new MigrationCandidate(resolved, MigrationAction.STRIP,
                    "version matches BOM", bomDep.version());
        } else {
            return new MigrationCandidate(resolved, MigrationAction.FLAG,
                    "version mismatch: POM has " + resolved.version() + ", BOM has " + bomDep.version(),
                    bomDep.version());
        }
    }

    private String resolveVersion(Dependency dep, PropertyInterpolator interpolator) {
        String version = dep.getVersion();
        if (version == null) {
            return null;
        }
        return interpolator.interpolate(version);
    }

    private PropertyInterpolator buildInterpolator(Model model) {
        PropertyInterpolator interpolator = new PropertyInterpolator(model.getProperties());
        interpolator.withProjectCoordinates(
                model.getGroupId() != null ? model.getGroupId()
                        : (model.getParent() != null ? model.getParent().getGroupId() : null),
                model.getArtifactId(),
                model.getVersion() != null ? model.getVersion()
                        : (model.getParent() != null ? model.getParent().getVersion() : null)
        );
        return interpolator;
    }

    private Model parseModel(Path pomFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(pomFile)) {
            return new MavenXpp3Reader().read(reader);
        } catch (XmlPullParserException e) {
            throw new IOException("Failed to parse POM: " + pomFile, e);
        }
    }
}
