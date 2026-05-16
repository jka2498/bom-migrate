package dev.jka.bommigrate.core.migrator;

import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationAction;
import dev.jka.bommigrate.core.model.MigrationCandidate;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.model.PomModelReader;
import dev.jka.bommigrate.core.model.ResolvedDependency;
import dev.jka.bommigrate.core.resolver.PropertyInterpolator;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes a target microservice POM against a BOM's dependency management map,
 * classifying each versioned dependency as STRIP, FLAG, or SKIP.
 */
public final class PomAnalyzer {

    private static final Pattern VERSION_RANGE_PATTERN = Pattern.compile("[\\[\\]()]");
    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{(.+?)}");


    /**
     * Analyzes the target POM and produces a migration report.
     *
     * @param targetPomPath path to the microservice pom.xml
     * @param bomMap        the resolved BOM dependency management map
     * @return migration report with classified candidates
     * @throws IOException on parse or file errors
     */
    public MigrationReport analyze(Path targetPomPath, DependencyManagementMap bomMap) throws IOException {
        return analyze(targetPomPath, bomMap, false);
    }

    /**
     * Analyzes the target POM with an option to force-strip version mismatches.
     *
     * @param forceStripMismatches when true, deps managed by the BOM are always STRIP
     *                             even if the version differs — for the web UI flow where
     *                             the user already confirmed the target version in the
     *                             candidates table
     */
    public MigrationReport analyze(Path targetPomPath, DependencyManagementMap bomMap,
                                    boolean forceStripMismatches) throws IOException {
        return analyze(targetPomPath, bomMap, forceStripMismatches, false);
    }

    MigrationReport analyze(Path targetPomPath, DependencyManagementMap bomMap,
                            boolean forceStripMismatches, boolean pluginsManaged) throws IOException {
        Model model = PomModelReader.parseModel(targetPomPath);
        PropertyInterpolator interpolator = PomModelReader.buildInterpolator(model);

        List<Dependency> dependencies = model.getDependencies();
        if (dependencies == null) {
            return new MigrationReport(targetPomPath, List.of());
        }

        // When plugins are NOT managed, collect properties referenced by plugin
        // versions so we can flag deps that share a property with a plugin.
        Set<String> pluginProperties = pluginsManaged
                ? Set.of()
                : collectPluginProperties(model);

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

            candidates.add(classify(resolved, rawVersion, bomMap, duplicateIndices.contains(i),
                    forceStripMismatches, pluginProperties));
        }

        return new MigrationReport(targetPomPath, candidates);
    }

    /**
     * Analyzes both dependencies AND plugins in the target POM.
     *
     * @param targetPomPath path to the microservice pom.xml
     * @param bomMap        the resolved BOM dependency management map
     * @param pluginBomMap  map of plugin groupId:artifactId → version managed by the parent
     * @return migration report with classified dependency and plugin candidates
     */
    public MigrationReport analyze(Path targetPomPath, DependencyManagementMap bomMap,
                                    DependencyManagementMap pluginBomMap) throws IOException {
        return analyze(targetPomPath, bomMap, pluginBomMap, false);
    }

    public MigrationReport analyze(Path targetPomPath, DependencyManagementMap bomMap,
                                    DependencyManagementMap pluginBomMap,
                                    boolean forceStripMismatches) throws IOException {
        boolean pluginsManaged = pluginBomMap != null && pluginBomMap.size() > 0;
        MigrationReport depReport = analyze(targetPomPath, bomMap, forceStripMismatches, pluginsManaged);

        if (pluginBomMap == null || pluginBomMap.size() == 0) {
            return depReport;
        }

        Model model = PomModelReader.parseModel(targetPomPath);
        if (model.getBuild() == null || model.getBuild().getPlugins() == null) {
            return depReport;
        }

        PropertyInterpolator interpolator = PomModelReader.buildInterpolator(model);
        List<MigrationCandidate> pluginCandidates = new ArrayList<>();

        for (Plugin plugin : model.getBuild().getPlugins()) {
            String groupId = plugin.getGroupId() != null ? plugin.getGroupId() : "org.apache.maven.plugins";
            String artifactId = plugin.getArtifactId();
            if (artifactId == null) continue;

            String rawVersion = plugin.getVersion();
            String resolvedVersion = rawVersion != null ? interpolator.interpolate(rawVersion) : null;

            ResolvedDependency resolved = new ResolvedDependency(
                    groupId, artifactId,
                    resolvedVersion != null ? resolvedVersion : "",
                    "maven-plugin", "");

            MigrationCandidate candidate = classifyPlugin(resolved, rawVersion, pluginBomMap, forceStripMismatches);
            pluginCandidates.add(candidate);
        }

        return new MigrationReport(targetPomPath, depReport.candidates(), pluginCandidates);
    }

    private MigrationCandidate classifyPlugin(ResolvedDependency resolved, String rawVersion,
                                               DependencyManagementMap pluginBomMap,
                                               boolean forceStripMismatches) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "already managed (no version tag)", null);
        }
        if (resolved.version().contains("${")) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "unresolvable property in version: " + resolved.version(), null);
        }

        Optional<ResolvedDependency> bomEntry = pluginBomMap.lookup(resolved.groupId(), resolved.artifactId());
        if (bomEntry.isEmpty()) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "not managed by parent", null);
        }

        ResolvedDependency bomPlugin = bomEntry.get();
        if (resolved.version().equals(bomPlugin.version())) {
            return new MigrationCandidate(resolved, MigrationAction.STRIP,
                    "version matches parent pluginManagement", bomPlugin.version());
        } else if (forceStripMismatches) {
            return new MigrationCandidate(resolved, MigrationAction.STRIP,
                    "version changed: service has " + resolved.version() + ", parent has " + bomPlugin.version(),
                    bomPlugin.version());
        } else {
            return new MigrationCandidate(resolved, MigrationAction.FLAG,
                    "version mismatch: POM has " + resolved.version() + ", parent has " + bomPlugin.version(),
                    bomPlugin.version());
        }
    }

    private MigrationCandidate classify(
            ResolvedDependency resolved,
            String rawVersion,
            DependencyManagementMap bomMap,
            boolean isDuplicate,
            boolean forceStripMismatches,
            Set<String> pluginProperties) {

        if (rawVersion == null || rawVersion.isBlank()) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "already managed (no version tag)", null);
        }

        if (isDuplicate) {
            return new MigrationCandidate(resolved, MigrationAction.FLAG,
                    "duplicate declaration", null);
        }

        if (VERSION_RANGE_PATTERN.matcher(resolved.version()).find()) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "version range detected", null);
        }

        if (resolved.version().contains("${")) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "unresolvable property in version: " + resolved.version(), null);
        }

        Optional<ResolvedDependency> bomEntry = bomMap.lookup(resolved.key());
        if (bomEntry.isEmpty()) {
            return new MigrationCandidate(resolved, MigrationAction.SKIP,
                    "not managed by BOM", null);
        }

        ResolvedDependency bomDep = bomEntry.get();

        // Guard: if this dep's version comes from a property that a plugin also
        // uses, stripping the dep version could leave the plugin and dep at
        // different versions. Flag it so the user knows plugins aren't managed.
        if (!pluginProperties.isEmpty() && rawVersion.contains("${")) {
            Matcher m = PROPERTY_REF.matcher(rawVersion);
            if (m.find() && pluginProperties.contains(m.group(1))) {
                return new MigrationCandidate(resolved, MigrationAction.FLAG,
                        "version property shared with plugin (plugins not managed)",
                        bomDep.version());
            }
        }

        if (resolved.version().equals(bomDep.version())) {
            return new MigrationCandidate(resolved, MigrationAction.STRIP,
                    "version matches BOM", bomDep.version());
        } else if (forceStripMismatches) {
            return new MigrationCandidate(resolved, MigrationAction.STRIP,
                    "version changed: service has " + resolved.version() + ", BOM has " + bomDep.version(),
                    bomDep.version());
        } else {
            return new MigrationCandidate(resolved, MigrationAction.FLAG,
                    "version mismatch: POM has " + resolved.version() + ", BOM has " + bomDep.version(),
                    bomDep.version());
        }
    }

    private Set<String> collectPluginProperties(Model model) {
        Set<String> properties = new HashSet<>();
        if (model.getBuild() == null || model.getBuild().getPlugins() == null) {
            return properties;
        }
        for (Plugin plugin : model.getBuild().getPlugins()) {
            String version = plugin.getVersion();
            if (version != null) {
                Matcher m = PROPERTY_REF.matcher(version);
                while (m.find()) {
                    properties.add(m.group(1));
                }
            }
        }
        return properties;
    }

    private String resolveVersion(Dependency dep, PropertyInterpolator interpolator) {
        String version = dep.getVersion();
        if (version == null) {
            return null;
        }
        return interpolator.interpolate(version);
    }
}
