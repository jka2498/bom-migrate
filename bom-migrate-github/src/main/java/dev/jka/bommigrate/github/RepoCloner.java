package dev.jka.bommigrate.github;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Shallow-clones Git repos using JGit. Tries shallow clone first (depth=1)
 * and falls back to a full clone with a warning if the shallow protocol is
 * not supported by the remote (e.g. some corporate proxies).
 */
public final class RepoCloner {

    /**
     * Shallow-clones the repo at the given HTTPS URL into {@code targetDir}.
     *
     * @param httpsUrl   the HTTPS clone URL
     * @param token      GitHub token used as the password for HTTPS auth
     * @param targetDir  the local directory to clone into (created if needed)
     * @return the path of the clone (same as {@code targetDir})
     * @throws IOException on clone or filesystem failures
     */
    public Path shallowClone(String httpsUrl, String token, Path targetDir) throws IOException {
        Files.createDirectories(targetDir.getParent() != null ? targetDir.getParent() : targetDir);

        var credentials = new UsernamePasswordCredentialsProvider("x-access-token", token);

        try {
            Git.cloneRepository()
                    .setURI(httpsUrl)
                    .setDirectory(targetDir.toFile())
                    .setDepth(1)
                    .setCredentialsProvider(credentials)
                    .call()
                    .close();
            return targetDir;
        } catch (GitAPIException shallowFailure) {
            // Clean up partial clone if it exists
            cleanupQuietly(targetDir);
            System.err.println("[WARN] Shallow clone failed for " + httpsUrl
                    + " — falling back to full clone. (" + shallowFailure.getMessage() + ")");
            try {
                Git.cloneRepository()
                        .setURI(httpsUrl)
                        .setDirectory(targetDir.toFile())
                        .setCredentialsProvider(credentials)
                        .call()
                        .close();
                return targetDir;
            } catch (GitAPIException fullFailure) {
                cleanupQuietly(targetDir);
                throw new IOException("Failed to clone " + httpsUrl, fullFailure);
            }
        }
    }

    /**
     * Recursively deletes the given clone directory.
     */
    public void cleanup(Path clonedPath) throws IOException {
        if (!Files.exists(clonedPath)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(clonedPath)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private void cleanupQuietly(Path path) {
        try {
            cleanup(path);
        } catch (IOException ignored) {
        }
    }
}
