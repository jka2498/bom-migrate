package dev.jka.bommigrate.github;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/**
 * Glob-based repo name filter. Supports standard shell glob patterns:
 * <ul>
 *   <li>{@code *} — matches any characters except {@code /}</li>
 *   <li>{@code ?} — matches any single character</li>
 *   <li>{@code [abc]} — matches any character in the set</li>
 *   <li>{@code {foo,bar}} — matches either alternative</li>
 * </ul>
 *
 * Example patterns: {@code claims-*}, {@code claims-digital-sl-*}, {@code claims-digital-*-service}.
 */
public final class RepoFilter {

    private final PathMatcher matcher;
    private final String pattern;

    /**
     * Creates a filter from a glob pattern. A null or empty pattern matches everything.
     */
    public RepoFilter(String pattern) {
        this.pattern = pattern;
        if (pattern == null || pattern.isBlank()) {
            this.matcher = null;
        } else {
            this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        }
    }

    /**
     * Returns true if the repo name matches the configured glob pattern.
     * Returns true for all names if no pattern was set.
     */
    public boolean matches(String repoName) {
        if (matcher == null) {
            return true;
        }
        return matcher.matches(Path.of(repoName));
    }

    public String pattern() {
        return pattern;
    }
}
