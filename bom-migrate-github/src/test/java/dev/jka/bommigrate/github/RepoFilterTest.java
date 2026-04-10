package dev.jka.bommigrate.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepoFilterTest {

    @Test
    void nullPatternMatchesEverything() {
        RepoFilter filter = new RepoFilter(null);
        assertThat(filter.matches("any-repo")).isTrue();
        assertThat(filter.matches("another")).isTrue();
    }

    @Test
    void blankPatternMatchesEverything() {
        RepoFilter filter = new RepoFilter("");
        assertThat(filter.matches("any-repo")).isTrue();
    }

    @Test
    void prefixGlobMatches() {
        RepoFilter filter = new RepoFilter("claims-*");
        assertThat(filter.matches("claims-digital")).isTrue();
        assertThat(filter.matches("claims-digital-sl-service")).isTrue();
        assertThat(filter.matches("claims")).isFalse();
        assertThat(filter.matches("other-repo")).isFalse();
    }

    @Test
    void multiSegmentPrefixGlobMatches() {
        RepoFilter filter = new RepoFilter("claims-digital-sl-*");
        assertThat(filter.matches("claims-digital-sl-service")).isTrue();
        assertThat(filter.matches("claims-digital-sl-worker")).isTrue();
        assertThat(filter.matches("claims-digital-other")).isFalse();
    }

    @Test
    void middleWildcardMatches() {
        RepoFilter filter = new RepoFilter("claims-digital-*-service");
        assertThat(filter.matches("claims-digital-sl-service")).isTrue();
        assertThat(filter.matches("claims-digital-foo-service")).isTrue();
        assertThat(filter.matches("claims-digital-sl-worker")).isFalse();
    }

    @Test
    void suffixGlobMatches() {
        RepoFilter filter = new RepoFilter("*-service");
        assertThat(filter.matches("foo-service")).isTrue();
        assertThat(filter.matches("bar-service")).isTrue();
        assertThat(filter.matches("foo-worker")).isFalse();
    }

    @Test
    void bracesMatchAlternatives() {
        RepoFilter filter = new RepoFilter("{foo,bar}-service");
        assertThat(filter.matches("foo-service")).isTrue();
        assertThat(filter.matches("bar-service")).isTrue();
        assertThat(filter.matches("baz-service")).isFalse();
    }

    @Test
    void patternIsReadable() {
        RepoFilter filter = new RepoFilter("claims-*");
        assertThat(filter.pattern()).isEqualTo("claims-*");
    }
}
