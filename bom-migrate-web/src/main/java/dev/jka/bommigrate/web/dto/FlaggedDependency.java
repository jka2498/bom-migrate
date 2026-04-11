package dev.jka.bommigrate.web.dto;

/**
 * A dependency that was flagged during migration preview — version is managed
 * by the BOM but differs from what the service declares, so the service would
 * need manual attention.
 */
public record FlaggedDependency(
        String groupId,
        String artifactId,
        String serviceVersion,
        String bomVersion,
        String reason
) {
}
