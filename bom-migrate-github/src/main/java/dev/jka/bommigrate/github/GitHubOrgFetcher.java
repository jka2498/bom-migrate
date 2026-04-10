package dev.jka.bommigrate.github;

import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists repositories in a GitHub organisation, with optional filtering by
 * name (glob pattern) and by primary language (Java family).
 *
 * <p>Handles pagination automatically and warns on low rate limit. The token
 * is never logged or exposed.
 */
public final class GitHubOrgFetcher {

    /** Languages considered "Java family" for the pre-filter. */
    private static final Set<String> JVM_LANGUAGES = new HashSet<>(List.of(
            "Java", "Kotlin", "Scala", "Groovy"
    ));

    private static final int LOW_RATE_LIMIT_THRESHOLD = 100;

    private final GitHub github;

    public GitHubOrgFetcher(String token) throws IOException {
        this.github = new GitHubBuilder().withOAuthToken(token).build();
    }

    /** Package-private constructor for testing with a pre-built client. */
    GitHubOrgFetcher(GitHub github) {
        this.github = github;
    }

    /**
     * Lists repositories in the given org, optionally filtered by glob pattern
     * and optionally restricted to JVM-language repos.
     *
     * @param orgName              GitHub organisation name
     * @param repoFilterGlob       optional glob pattern (null or blank = no filter)
     * @param javaOnly             if true, only include Java/Kotlin/Scala/Groovy primary-language repos
     * @return matching repositories
     */
    public List<GHRepository> listRepos(String orgName, String repoFilterGlob, boolean javaOnly) throws IOException {
        warnIfRateLimited();

        GHOrganization org = github.getOrganization(orgName);
        RepoFilter filter = new RepoFilter(repoFilterGlob);

        List<GHRepository> result = new ArrayList<>();
        for (GHRepository repo : org.listRepositories().withPageSize(100)) {
            if (!filter.matches(repo.getName())) {
                continue;
            }
            if (javaOnly && !isJvmLanguage(repo)) {
                continue;
            }
            result.add(repo);
        }
        return result;
    }

    /**
     * Returns repo names that were filtered out by the JVM language pre-filter.
     * Useful for reporting which repos were skipped and why.
     */
    public List<String> listSkippedByLanguage(String orgName, String repoFilterGlob) throws IOException {
        GHOrganization org = github.getOrganization(orgName);
        RepoFilter filter = new RepoFilter(repoFilterGlob);

        List<String> skipped = new ArrayList<>();
        for (GHRepository repo : org.listRepositories().withPageSize(100)) {
            if (filter.matches(repo.getName()) && !isJvmLanguage(repo)) {
                skipped.add(repo.getName());
            }
        }
        return skipped;
    }

    /**
     * Emits a warning to stderr if the current rate limit is below the threshold.
     */
    public void warnIfRateLimited() {
        try {
            GHRateLimit rateLimit = github.getRateLimit();
            int remaining = rateLimit.getRemaining();
            if (remaining < LOW_RATE_LIMIT_THRESHOLD) {
                System.err.println("[WARN] GitHub API rate limit low: " + remaining
                        + " requests remaining. Resets at " + rateLimit.getResetDate());
            }
        } catch (IOException e) {
            // Non-fatal: if we can't read rate limit, just continue
        }
    }

    private boolean isJvmLanguage(GHRepository repo) {
        try {
            String language = repo.getLanguage();
            return language != null && JVM_LANGUAGES.contains(language);
        } catch (Exception e) {
            // If we can't determine language, include by default (err on inclusion)
            return true;
        }
    }
}
