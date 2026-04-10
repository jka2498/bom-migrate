package dev.jka.bommigrate.github;

import java.util.Collections;
import java.util.List;

/**
 * Result of scanning a GitHub organisation: which repos were cloned successfully,
 * which were skipped (by language filter or missing POM), and which failed to clone.
 *
 * @param clonedRepos         successfully cloned repos with at least one pom.xml found
 * @param skippedByLanguage   repo names filtered out by the Java language pre-filter
 * @param skippedNoPom        repo names that cloned successfully but had no pom.xml
 * @param failedToClone       repos that failed to clone, with a reason
 */
public record OrgScanResult(
        List<ClonedRepo> clonedRepos,
        List<String> skippedByLanguage,
        List<String> skippedNoPom,
        List<FailedRepo> failedToClone
) {

    public OrgScanResult {
        clonedRepos = Collections.unmodifiableList(clonedRepos);
        skippedByLanguage = Collections.unmodifiableList(skippedByLanguage);
        skippedNoPom = Collections.unmodifiableList(skippedNoPom);
        failedToClone = Collections.unmodifiableList(failedToClone);
    }

    /** A repo that failed to clone. */
    public record FailedRepo(String repoName, String reason) {}

    /** All POM paths found across all successfully cloned repos. */
    public List<java.nio.file.Path> allPomPaths() {
        return clonedRepos.stream()
                .flatMap(r -> r.pomPaths().stream())
                .toList();
    }
}
