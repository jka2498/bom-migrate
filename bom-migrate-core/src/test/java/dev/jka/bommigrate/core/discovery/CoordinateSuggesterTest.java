package dev.jka.bommigrate.core.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoordinateSuggesterTest {

    private final CoordinateSuggester suggester = new CoordinateSuggester();

    @Test
    void longestCommonPrefixFindsSharedSegments() {
        assertThat(CoordinateSuggester.longestCommonPrefix(List.of(
                "com.mycompany.claims.service-a",
                "com.mycompany.claims.service-b"
        ))).isEqualTo("com.mycompany.claims");

        assertThat(CoordinateSuggester.longestCommonPrefix(List.of(
                "com.mycompany.claims.service-a",
                "com.mycompany.billing.service-b"
        ))).isEqualTo("com.mycompany");

        assertThat(CoordinateSuggester.longestCommonPrefix(List.of(
                "com.alpha", "net.beta"
        ))).isNull();

        assertThat(CoordinateSuggester.longestCommonPrefix(List.of("only.one"))).isEqualTo("only.one");
    }

    @Test
    void emptyListFallsBackToDefaults() {
        CoordinateSuggester.Suggestion s = suggester.suggest(List.of());
        assertThat(s.groupId()).isEqualTo("com.example");
        assertThat(s.artifactId()).isEqualTo("my-bom");
        assertThat(s.version()).isEqualTo("1.0.0");
    }

    @Test
    void suggestsCommonPrefixFromScannedPoms(@TempDir Path tempDir) throws IOException {
        Path a = tempDir.resolve("a.xml");
        Path b = tempDir.resolve("b.xml");

        Files.writeString(a, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.mycompany.claims.service-a</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        Files.writeString(b, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.mycompany.claims.service-b</groupId>
                    <artifactId>service-b</artifactId>
                    <version>1.0.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        CoordinateSuggester.Suggestion s = suggester.suggest(List.of(a, b));
        assertThat(s.groupId()).isEqualTo("com.mycompany.claims");
    }

    @Test
    void usesParentGroupIdWhenOwnGroupIdMissing(@TempDir Path tempDir) throws IOException {
        Path a = tempDir.resolve("a.xml");

        Files.writeString(a, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.inherited</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        CoordinateSuggester.Suggestion s = suggester.suggest(List.of(a));
        assertThat(s.groupId()).isEqualTo("com.inherited");
    }
}
