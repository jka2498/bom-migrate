package dev.jka.bommigrate.github;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * A successfully cloned repository with its discovered POM files.
 *
 * @param repoName    the GitHub repo name (e.g. "claims-digital-service")
 * @param remoteUrl   the HTTPS remote URL used for cloning
 * @param clonePath   the local path where the repo was cloned
 * @param pomPaths    all {@code pom.xml} files found within the clone
 */
public record ClonedRepo(
        String repoName,
        String remoteUrl,
        Path clonePath,
        List<Path> pomPaths
) {
    public ClonedRepo {
        pomPaths = Collections.unmodifiableList(pomPaths);
    }
}
