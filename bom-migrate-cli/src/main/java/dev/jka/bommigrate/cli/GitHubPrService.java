package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.model.MigrationAction;
import dev.jka.bommigrate.core.model.MigrationCandidate;
import dev.jka.bommigrate.core.model.MigrationReport;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Creates GitHub pull requests with BOM migration changes.
 * Uses the {@code org.kohsuke:github-api} library.
 *
 * <p>The GitHub token must never be logged or printed.
 */
public final class GitHubPrService {

    private final GitHub github;

    public GitHubPrService(String token) throws IOException {
        this.github = new GitHubBuilder()
                .withOAuthToken(token)
                .build();
    }

    /**
     * Creates a pull request for the given migration report.
     *
     * @param report  the migration report for a single POM
     * @param bomPath the BOM path used (for the PR description)
     * @return the URL of the created pull request
     * @throws IOException on GitHub API errors
     */
    public String createPullRequest(MigrationReport report, Path bomPath) throws IOException {
        Path repoRoot = findGitRoot(report.pomPath());
        if (repoRoot == null) {
            throw new IOException("Could not find git repository root for " + report.pomPath());
        }

        String remoteUrl = getRemoteUrl(repoRoot);
        String repoFullName = extractRepoFullName(remoteUrl);

        GHRepository repo = github.getRepository(repoFullName);

        String branchName = "bom-migrate/" + System.currentTimeMillis();
        String title = "chore: remove BOM-managed version tags";
        String body = buildPrBody(report, bomPath);

        // The actual git operations (branch, commit, push) would need to be done
        // via local git commands before creating the PR. This is a simplified
        // implementation that creates the PR assuming changes are already pushed.
        var pr = repo.createPullRequest(
                title,
                branchName,
                repo.getDefaultBranch(),
                body
        );

        return pr.getHtmlUrl().toString();
    }

    private String buildPrBody(MigrationReport report, Path bomPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("## BOM Migration\n\n");
        sb.append("Automated migration using `bom-migrate` against BOM: `").append(bomPath).append("`\n\n");

        sb.append("### Changes\n\n");
        sb.append("| Dependency | Action | Details |\n");
        sb.append("|---|---|---|\n");

        for (MigrationCandidate candidate : report.candidates()) {
            var dep = candidate.dependency();
            String depName = dep.groupId() + ":" + dep.artifactId();
            sb.append("| `").append(depName).append("` | ")
                    .append(candidate.action()).append(" | ")
                    .append(candidate.reason()).append(" |\n");
        }

        sb.append("\n### Summary\n\n");
        sb.append("- **").append(report.stripCount()).append("** version tags removed (matched BOM)\n");
        sb.append("- **").append(report.flagCount()).append("** dependencies flagged for review\n");
        sb.append("- **").append(report.skipCount()).append("** dependencies skipped\n");

        return sb.toString();
    }

    private Path findGitRoot(Path path) {
        Path current = path.toAbsolutePath().getParent();
        while (current != null) {
            if (current.resolve(".git").toFile().exists()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private String getRemoteUrl(Path repoRoot) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "remote", "get-url", "origin");
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to get git remote URL");
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while getting git remote URL", e);
        }
    }

    private String extractRepoFullName(String remoteUrl) {
        // Handle both HTTPS and SSH URLs
        // https://github.com/owner/repo.git -> owner/repo
        // git@github.com:owner/repo.git -> owner/repo
        String name = remoteUrl
                .replaceFirst(".*github\\.com[:/]", "")
                .replaceFirst("\\.git$", "")
                .trim();
        return name;
    }
}
