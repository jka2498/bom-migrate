package dev.jka.bommigrate.core.discovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to generate the final multi-module BOM structure:
 * parent coordinates, the defined modules, user-confirmed assignments,
 * and how versions should be formatted in the generated POMs.
 *
 * @param parentGroupId     groupId for the generated BOM parent POM
 * @param parentArtifactId  artifactId for the generated BOM parent POM
 * @param parentVersion     version for the generated BOM parent POM
 * @param modules           user-defined BOM modules
 * @param assignments       confirmed candidate-to-module assignments
 * @param versionFormat     how version values should be emitted (inline or via properties)
 */
public record BomGenerationPlan(
        String parentGroupId,
        String parentArtifactId,
        String parentVersion,
        List<BomModule> modules,
        List<BomModuleAssignment> assignments,
        VersionFormat versionFormat
) {

    public BomGenerationPlan {
        if (parentGroupId == null || parentGroupId.isBlank()) {
            throw new IllegalArgumentException("parentGroupId must not be blank");
        }
        if (parentArtifactId == null || parentArtifactId.isBlank()) {
            throw new IllegalArgumentException("parentArtifactId must not be blank");
        }
        if (parentVersion == null || parentVersion.isBlank()) {
            throw new IllegalArgumentException("parentVersion must not be blank");
        }
        modules = Collections.unmodifiableList(modules);
        assignments = Collections.unmodifiableList(assignments);
        if (versionFormat == null) {
            versionFormat = VersionFormat.INLINE;
        }

        // Validate: every assignment's module must exist in the modules list
        for (BomModuleAssignment assignment : assignments) {
            if (!modules.contains(assignment.module())) {
                throw new IllegalArgumentException(
                        "Assignment references unknown module: " + assignment.module().name());
            }
        }
    }

    /**
     * Backward-compatible constructor that defaults to {@link VersionFormat#INLINE}.
     */
    public BomGenerationPlan(
            String parentGroupId,
            String parentArtifactId,
            String parentVersion,
            List<BomModule> modules,
            List<BomModuleAssignment> assignments
    ) {
        this(parentGroupId, parentArtifactId, parentVersion, modules, assignments, VersionFormat.INLINE);
    }

    /** Groups assignments by their assigned module, preserving insertion order. */
    public Map<BomModule, List<BomModuleAssignment>> groupedByModule() {
        Map<BomModule, List<BomModuleAssignment>> grouped = new LinkedHashMap<>();
        for (BomModule module : modules) {
            grouped.put(module, new ArrayList<>());
        }
        for (BomModuleAssignment assignment : assignments) {
            grouped.get(assignment.module()).add(assignment);
        }
        return grouped;
    }

    /** True if the plan produces a single flat BOM (no child modules). */
    public boolean isSingleModule() {
        return modules.size() <= 1;
    }
}
