package dev.jka.bommigrate.web.dto;

/**
 * A dependency whose version was changed by the migration (the service had
 * one version, the BOM has a different one, and the user confirmed the BOM
 * version in the candidates table). Shown as an info note in the service
 * preview so the user knows which strips involved a version bump.
 */
public record VersionChange(
        String groupId,
        String artifactId,
        String serviceVersion,
        String bomVersion
) {
}
