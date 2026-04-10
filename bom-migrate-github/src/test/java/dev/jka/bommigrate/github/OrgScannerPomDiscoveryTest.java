package dev.jka.bommigrate.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the POM discovery logic in OrgScanner without hitting the network.
 * Creates synthetic directory structures and verifies the scanner finds
 * the right files while skipping well-known exclude dirs.
 */
class OrgScannerPomDiscoveryTest {

    private final OrgScanner scanner = new OrgScanner(null, null);

    @Test
    void findsSinglePomAtRoot(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        List<Path> poms = scanner.findPomFiles(dir);
        assertThat(poms).hasSize(1).contains(dir.resolve("pom.xml"));
    }

    @Test
    void findsNestedPomsInMonorepo(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path serviceA = dir.resolve("service-a");
        Path serviceB = dir.resolve("service-b");
        Files.createDirectories(serviceA);
        Files.createDirectories(serviceB);
        Files.writeString(serviceA.resolve("pom.xml"), "<project/>");
        Files.writeString(serviceB.resolve("pom.xml"), "<project/>");

        List<Path> poms = scanner.findPomFiles(dir);
        assertThat(poms).hasSize(3);
    }

    @Test
    void skipsTargetDirectory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path targetDir = dir.resolve("target");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("pom.xml"), "<project/>");

        List<Path> poms = scanner.findPomFiles(dir);
        assertThat(poms).hasSize(1).contains(dir.resolve("pom.xml"));
    }

    @Test
    void skipsNodeModulesDirectory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path nodeModules = dir.resolve("node_modules").resolve("some-pkg");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("pom.xml"), "<project/>");

        List<Path> poms = scanner.findPomFiles(dir);
        assertThat(poms).hasSize(1);
    }

    @Test
    void skipsGitDirectory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path gitDir = dir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("pom.xml"), "<project/>");

        List<Path> poms = scanner.findPomFiles(dir);
        assertThat(poms).hasSize(1);
    }

    @Test
    void emptyRepoReturnsEmptyList(@TempDir Path dir) throws IOException {
        List<Path> poms = scanner.findPomFiles(dir);
        assertThat(poms).isEmpty();
    }

    @Test
    void nonExistentRootReturnsEmptyList(@TempDir Path dir) throws IOException {
        List<Path> poms = scanner.findPomFiles(dir.resolve("does-not-exist"));
        assertThat(poms).isEmpty();
    }
}
