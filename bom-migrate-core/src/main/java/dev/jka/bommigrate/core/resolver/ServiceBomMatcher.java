package dev.jka.bommigrate.core.resolver;

import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.PomModelReader;
import dev.jka.bommigrate.core.model.ResolvedDependency;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Determines which child modules of a multi-module BOM are actually imported
 * by a given service POM, then builds a filtered {@link DependencyManagementMap}
 * containing only the deps from those matched modules. This prevents stripping
 * versions for deps managed by child modules the service doesn't use.
 *
 * <p>For a single-module BOM (no children), or when the service imports the
 * parent aggregator directly, the full map is returned unchanged.
 */
public final class ServiceBomMatcher {

    private final DefaultBomResolver resolver = new DefaultBomResolver();

    public record MatchResult(
            DependencyManagementMap effectiveBomMap,
            List<String> matchedModules,
            List<String> unmatchedImports
    ) {}

    /**
     * Matches the service POM's BOM imports against the user's generated BOM
     * structure and returns the subset of managed deps that apply to this service.
     *
     * @param servicePomPath   path to the service pom.xml
     * @param bomOutputDir     directory containing the generated BOM (parent pom + child dirs)
     * @param fullBomMap       the complete BOM map with all children merged (fallback)
     * @param bomGroupId       groupId of the generated BOM
     * @param bomArtifactId    artifactId of the generated BOM parent
     * @param childModuleNames names of the child BOM modules (empty for single-module BOMs)
     * @return the effective BOM map for this service, plus metadata about what matched
     */
    public MatchResult match(
            Path servicePomPath,
            Path bomOutputDir,
            DependencyManagementMap fullBomMap,
            String bomGroupId,
            String bomArtifactId,
            List<String> childModuleNames
    ) throws IOException {

        // Single-module BOM or no children → full map always
        if (childModuleNames == null || childModuleNames.isEmpty()) {
            return new MatchResult(fullBomMap, List.of(), List.of());
        }

        Model serviceModel = PomModelReader.parseModel(servicePomPath);
        DependencyManagement depMgmt = serviceModel.getDependencyManagement();
        if (depMgmt == null || depMgmt.getDependencies() == null) {
            // No <dependencyManagement> at all — service will get imports added later.
            // Use full map as fallback.
            return new MatchResult(fullBomMap, List.of(), List.of());
        }

        PropertyInterpolator interpolator = PomModelReader.buildInterpolator(serviceModel);

        List<String> matchedModules = new ArrayList<>();
        List<String> unmatchedImports = new ArrayList<>();
        boolean importsParent = false;

        for (Dependency dep : depMgmt.getDependencies()) {
            if (!"pom".equals(dep.getType()) || !"import".equals(dep.getScope())) {
                continue;
            }

            String importGroupId = interpolator.interpolate(
                    dep.getGroupId() != null ? dep.getGroupId() : "");
            String importArtifactId = interpolator.interpolate(
                    dep.getArtifactId() != null ? dep.getArtifactId() : "");

            if (!bomGroupId.equals(importGroupId)) {
                // Different groupId entirely — not our BOM
                unmatchedImports.add(importGroupId + ":" + importArtifactId);
                continue;
            }

            // Same groupId — is it the parent aggregator?
            if (bomArtifactId.equals(importArtifactId)) {
                importsParent = true;
                continue;
            }

            // Is it one of the child modules?
            if (childModuleNames.contains(importArtifactId)) {
                matchedModules.add(importArtifactId);
            } else {
                unmatchedImports.add(importGroupId + ":" + importArtifactId);
            }
        }

        // If the service imports the parent directly, use all children
        if (importsParent) {
            return new MatchResult(fullBomMap, childModuleNames, unmatchedImports);
        }

        // If no child modules matched, fall back to the full map. The user
        // hasn't added the import yet (they'll paste the snippet later), or
        // the BOM is referenced by a different mechanism (parent inheritance).
        if (matchedModules.isEmpty()) {
            return new MatchResult(fullBomMap, List.of(), unmatchedImports);
        }

        // Resolve only the matched child modules and merge their deps
        List<ResolvedDependency> filteredDeps = new ArrayList<>();
        for (String moduleName : matchedModules) {
            Path childDir = bomOutputDir.resolve(moduleName);
            try {
                DependencyManagementMap childMap = resolver.resolve(childDir, false);
                filteredDeps.addAll(childMap.all());
            } catch (IOException e) {
                // Can't resolve this child — fall back to including full map
                return new MatchResult(fullBomMap, matchedModules, unmatchedImports);
            }
        }

        return new MatchResult(
                new DependencyManagementMap(filteredDeps),
                matchedModules,
                unmatchedImports
        );
    }
}
