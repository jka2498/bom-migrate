package dev.jka.bommigrate.core.model;

import org.apache.maven.model.Dependency;

/**
 * A fully resolved Maven dependency with interpolated version.
 * Type defaults to "jar" and classifier defaults to empty string when not specified.
 */
public record ResolvedDependency(
        String groupId,
        String artifactId,
        String version,
        String type,
        String classifier
) {

    public ResolvedDependency {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        type = (type == null || type.isBlank()) ? "jar" : type;
        classifier = (classifier == null) ? "" : classifier;
    }

    /**
     * Canonical lookup key: "groupId:artifactId:type:classifier".
     */
    public String key() {
        return groupId + ":" + artifactId + ":" + type + ":" + classifier;
    }

    /**
     * Creates a ResolvedDependency from a Maven model Dependency, using the raw
     * (possibly uninterpolated) version. Callers should interpolate before calling
     * this if property resolution is needed.
     */
    public static ResolvedDependency fromMavenDependency(Dependency dep) {
        return new ResolvedDependency(
                dep.getGroupId(),
                dep.getArtifactId(),
                dep.getVersion(),
                dep.getType(),
                dep.getClassifier()
        );
    }

    /**
     * Creates a ResolvedDependency with an interpolated version, sourced from a Maven
     * model Dependency.
     */
    public static ResolvedDependency fromMavenDependency(Dependency dep, String resolvedVersion) {
        return new ResolvedDependency(
                dep.getGroupId(),
                dep.getArtifactId(),
                resolvedVersion,
                dep.getType(),
                dep.getClassifier()
        );
    }
}
