package dev.jka.bommigrate.core.migrator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PomDiffTest {

    @Test
    void identicalContentHasNoChanges() {
        String content = "line1\nline2\nline3\n";
        PomDiff diff = PomDiff.between(content, content);
        assertThat(diff.hasChanges()).isFalse();
        assertThat(diff.lines()).allMatch(l -> l.status() == PomDiff.Status.UNCHANGED);
    }

    @Test
    void removedLineMarkedAsRemoved() {
        String original = "line1\nline2\nline3\n";
        String modified = "line1\nline3\n";
        PomDiff diff = PomDiff.between(original, modified);
        assertThat(diff.hasChanges()).isTrue();
        assertThat(diff.lines())
                .anyMatch(l -> l.status() == PomDiff.Status.REMOVED && l.content().equals("line2"));
    }

    @Test
    void addedLineMarkedAsAdded() {
        String original = "line1\nline3\n";
        String modified = "line1\nline2\nline3\n";
        PomDiff diff = PomDiff.between(original, modified);
        assertThat(diff.hasChanges()).isTrue();
        assertThat(diff.lines())
                .anyMatch(l -> l.status() == PomDiff.Status.ADDED && l.content().equals("line2"));
    }

    @Test
    void bothAddedAndRemovedTogether() {
        String original = """
                <dependency>
                    <groupId>g</groupId>
                    <artifactId>a</artifactId>
                    <version>1.0</version>
                </dependency>
                """;
        String modified = """
                <dependency>
                    <groupId>g</groupId>
                    <artifactId>a</artifactId>
                </dependency>
                """;
        PomDiff diff = PomDiff.between(original, modified);
        assertThat(diff.hasChanges()).isTrue();
        long removed = diff.lines().stream().filter(l -> l.status() == PomDiff.Status.REMOVED).count();
        long added = diff.lines().stream().filter(l -> l.status() == PomDiff.Status.ADDED).count();
        assertThat(removed).isEqualTo(1);
        assertThat(added).isZero();
    }

    @Test
    void lineNumbersAreCorrect() {
        String original = "a\nb\nc\n";
        String modified = "a\nx\nc\n";
        PomDiff diff = PomDiff.between(original, modified);

        PomDiff.Line removed = diff.lines().stream()
                .filter(l -> l.status() == PomDiff.Status.REMOVED)
                .findFirst()
                .orElseThrow();
        assertThat(removed.content()).isEqualTo("b");
        assertThat(removed.oldNumber()).isEqualTo(2);
        assertThat(removed.newNumber()).isEqualTo(-1);

        PomDiff.Line added = diff.lines().stream()
                .filter(l -> l.status() == PomDiff.Status.ADDED)
                .findFirst()
                .orElseThrow();
        assertThat(added.content()).isEqualTo("x");
        assertThat(added.oldNumber()).isEqualTo(-1);
        assertThat(added.newNumber()).isEqualTo(2);
    }
}
