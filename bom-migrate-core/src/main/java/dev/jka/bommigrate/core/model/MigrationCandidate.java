package dev.jka.bommigrate.core.model;

/**
 * A dependency in a microservice POM together with its migration classification.
 *
 * @param dependency  the resolved dependency from the target POM
 * @param action      STRIP, FLAG, or SKIP
 * @param reason      human-readable explanation of why this action was chosen
 * @param bomVersion  the BOM's version for this dependency, or null if not managed
 */
public record MigrationCandidate(
        ResolvedDependency dependency,
        MigrationAction action,
        String reason,
        String bomVersion
) {
}
