package dev.jka.bommigrate.core.resolver;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyInterpolatorTest {

    @Test
    void simplePropertyResolution() {
        Properties props = new Properties();
        props.setProperty("spring.version", "6.1.0");

        var interpolator = new PropertyInterpolator(props);
        assertThat(interpolator.interpolate("${spring.version}")).isEqualTo("6.1.0");
    }

    @Test
    void chainedPropertyResolution() {
        Properties props = new Properties();
        props.setProperty("base.version", "6.1.0");
        props.setProperty("spring.version", "${base.version}");

        var interpolator = new PropertyInterpolator(props);
        assertThat(interpolator.interpolate("${spring.version}")).isEqualTo("6.1.0");
    }

    @Test
    void projectVersionResolution() {
        var interpolator = new PropertyInterpolator(null)
                .withProjectCoordinates("com.example", "my-app", "2.0.0");

        assertThat(interpolator.interpolate("${project.version}")).isEqualTo("2.0.0");
        assertThat(interpolator.interpolate("${project.groupId}")).isEqualTo("com.example");
        assertThat(interpolator.interpolate("${project.artifactId}")).isEqualTo("my-app");
    }

    @Test
    void pomVersionAlias() {
        var interpolator = new PropertyInterpolator(null)
                .withProjectCoordinates("com.example", "my-app", "2.0.0");

        assertThat(interpolator.interpolate("${pom.version}")).isEqualTo("2.0.0");
    }

    @Test
    void unresolvedPropertyLeftAsIs() {
        var interpolator = new PropertyInterpolator(null);

        String result = interpolator.interpolate("${unknown.prop}");
        assertThat(result).isEqualTo("${unknown.prop}");
    }

    @Test
    void hasUnresolved() {
        var interpolator = new PropertyInterpolator(null);

        assertThat(interpolator.hasUnresolved("${foo}")).isTrue();
        assertThat(interpolator.hasUnresolved("1.0.0")).isFalse();
        assertThat(interpolator.hasUnresolved(null)).isFalse();
    }

    @Test
    void nullInputReturnsNull() {
        var interpolator = new PropertyInterpolator(null);
        assertThat(interpolator.interpolate(null)).isNull();
    }

    @Test
    void noPlaceholderReturnsSameString() {
        Properties props = new Properties();
        var interpolator = new PropertyInterpolator(props);

        assertThat(interpolator.interpolate("1.0.0")).isEqualTo("1.0.0");
    }

    @Test
    void additionalProperties() {
        Properties base = new Properties();
        base.setProperty("a", "1");

        Properties extra = new Properties();
        extra.setProperty("b", "2");
        extra.setProperty("a", "overridden"); // should not override existing

        var interpolator = new PropertyInterpolator(base)
                .withAdditionalProperties(extra);

        assertThat(interpolator.interpolate("${a}")).isEqualTo("1");
        assertThat(interpolator.interpolate("${b}")).isEqualTo("2");
    }
}
