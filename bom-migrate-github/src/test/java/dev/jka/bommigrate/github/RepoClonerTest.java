package dev.jka.bommigrate.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests cleanup behaviour of RepoCloner without hitting the network.
 * The clone() methods themselves require a real Git remote and are exercised
 * only in integration tests.
 */
class RepoClonerTest {

    private final RepoCloner cloner = new RepoCloner();

    @Test
    void cleanupRemovesClonedFiles(@TempDir Path tempDir) throws IOException {
        Path cloneDir = tempDir.resolve("repo");
        Files.createDirectories(cloneDir);
        Files.writeString(cloneDir.resolve("pom.xml"), "<project/>");
        Files.createDirectories(cloneDir.resolve(".git"));
        Files.writeString(cloneDir.resolve(".git").resolve("HEAD"), "ref: main");

        cloner.cleanup(cloneDir);

        assertThat(Files.exists(cloneDir)).isFalse();
    }

    @Test
    void cleanupOnNonExistentPathIsNoop(@TempDir Path tempDir) throws IOException {
        Path cloneDir = tempDir.resolve("never-cloned");
        cloner.cleanup(cloneDir);
        assertThat(Files.exists(cloneDir)).isFalse();
    }

    @Test
    void cleanupRemovesNestedDirectories(@TempDir Path tempDir) throws IOException {
        Path cloneDir = tempDir.resolve("repo");
        Path nested = cloneDir.resolve("src").resolve("main").resolve("java");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("Foo.java"), "class Foo {}");

        cloner.cleanup(cloneDir);

        assertThat(Files.exists(cloneDir)).isFalse();
    }
}
