package dev.jka.bommigrate.github;

import org.kohsuke.github.GHRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Orchestrates the full flow of scanning a GitHub organisation:
 * <ol>
 *   <li>List repos via {@link GitHubOrgFetcher} (filtered by glob and JVM language)</li>
 *   <li>Shallow-clone each matching repo via {@link RepoCloner}</li>
 *   <li>Recursively discover all {@code pom.xml} files within each clone</li>
 *   <li>Report successes, skips (no POM), and failures</li>
 * </ol>
 *
 * <p>Non-JVM repos and clone failures are reported but do not abort the scan.
 */
public final class OrgScanner {

    private static final Set<String> SKIP_DIRS = Set.of("target", ".mvn", "node_modules", ".git");

    private final GitHubOrgFetcher fetcher;
    private final RepoCloner cloner;

    public OrgScanner(String token) throws IOException {
        this(new GitHubOrgFetcher(token), new RepoCloner());
    }

    /** Package-private constructor for testing. */
    OrgScanner(GitHubOrgFetcher fetcher, RepoCloner cloner) {
        this.fetcher = fetcher;
        this.cloner = cloner;
    }

    /**
     * Runs the full scan.
     *
     * @param orgName              GitHub organisation name
     * @param repoFilter           glob pattern (or null for no filter)
     * @param includeAllLanguages  skip the JVM language pre-filter
     * @param token                GitHub token for HTTPS clone auth
     * @param cloneDir             base directory for clones
     * @param keepClones           if true, do not register cleanup hooks
     * @return scan result with successes, skips, and failures
     */
    public OrgScanResult scan(String orgName,
                               String repoFilter,
                               boolean includeAllLanguages,
                               String token,
                               Path cloneDir,
                               boolean keepClones) throws IOException {
        List<ClonedRepo> clonedRepos = new ArrayList<>();
        List<String> skippedByLanguage = new ArrayList<>();
        List<String> skippedNoPom = new ArrayList<>();
        List<OrgScanResult.FailedRepo> failedToClone = new ArrayList<>();

        boolean javaOnly = !includeAllLanguages;
        List<GHRepository> matchingRepos = fetcher.listRepos(orgName, repoFilter, javaOnly);

        if (javaOnly) {
            skippedByLanguage.addAll(fetcher.listSkippedByLanguage(orgName, repoFilter));
        }

        Files.createDirectories(cloneDir);

        for (GHRepository repo : matchingRepos) {
            Path target = cloneDir.resolve(repo.getName());
            try {
                cloner.shallowClone(repo.getHttpTransportUrl(), token, target);
            } catch (IOException e) {
                failedToClone.add(new OrgScanResult.FailedRepo(repo.getName(), e.getMessage()));
                continue;
            }

            List<Path> pomPaths = findPomFiles(target);
            if (pomPaths.isEmpty()) {
                skippedNoPom.add(repo.getName());
                if (!keepClones) {
                    try {
                        cloner.cleanup(target);
                    } catch (IOException ignored) {
                    }
                }
                continue;
            }

            clonedRepos.add(new ClonedRepo(
                    repo.getName(),
                    repo.getHttpTransportUrl(),
                    target,
                    pomPaths
            ));

            if (!keepClones) {
                registerShutdownCleanup(target);
            }
        }

        return new OrgScanResult(clonedRepos, skippedByLanguage, skippedNoPom, failedToClone);
    }

    /**
     * Recursively finds all {@code pom.xml} files under {@code root}, skipping
     * well-known non-source directories ({@code target}, {@code .mvn}, etc.).
     */
    List<Path> findPomFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .filter(p -> !isUnderSkipDir(root, p))
                .forEach(result::add);
        }
        return result;
    }

    private boolean isUnderSkipDir(Path root, Path pom) {
        Path relative = root.relativize(pom);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (SKIP_DIRS.contains(relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    private void registerShutdownCleanup(Path path) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                cloner.cleanup(path);
            } catch (IOException ignored) {
            }
        }));
    }
}
