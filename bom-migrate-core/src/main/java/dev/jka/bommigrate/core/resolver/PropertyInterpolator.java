package dev.jka.bommigrate.core.resolver;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${...}} property placeholders against a map of known properties.
 * Supports iterative resolution (a property value referencing another property)
 * and special {@code ${project.*}} keys.
 */
public final class PropertyInterpolator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_ITERATIONS = 10;

    private final Map<String, String> properties;

    public PropertyInterpolator(Properties projectProperties) {
        this.properties = new HashMap<>();
        if (projectProperties != null) {
            for (String name : projectProperties.stringPropertyNames()) {
                this.properties.put(name, projectProperties.getProperty(name));
            }
        }
    }

    /**
     * Adds project-level special properties (project.version, project.groupId, etc.).
     */
    public PropertyInterpolator withProjectCoordinates(String groupId, String artifactId, String version) {
        if (groupId != null) {
            properties.put("project.groupId", groupId);
            properties.put("pom.groupId", groupId);
        }
        if (artifactId != null) {
            properties.put("project.artifactId", artifactId);
            properties.put("pom.artifactId", artifactId);
        }
        if (version != null) {
            properties.put("project.version", version);
            properties.put("pom.version", version);
        }
        return this;
    }

    /**
     * Adds additional properties (e.g., from a parent POM).
     */
    public PropertyInterpolator withAdditionalProperties(Properties extra) {
        if (extra != null) {
            for (String name : extra.stringPropertyNames()) {
                properties.putIfAbsent(name, extra.getProperty(name));
            }
        }
        return this;
    }

    /**
     * Resolves all {@code ${...}} placeholders in the given value.
     * Iterates up to {@link #MAX_ITERATIONS} times to handle chained references.
     * Unresolvable placeholders are left as-is.
     *
     * @param value the string potentially containing placeholders
     * @return the resolved string
     */
    public String interpolate(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            String resolved = resolveOnce(result);
            if (resolved.equals(result)) {
                break;
            }
            result = resolved;
        }
        return result;
    }

    /**
     * Returns true if the value still contains unresolved {@code ${...}} placeholders.
     */
    public boolean hasUnresolved(String value) {
        return value != null && PLACEHOLDER.matcher(value).find();
    }

    private String resolveOnce(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = properties.get(key);
            if (replacement != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                // Leave unresolvable placeholder as-is
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
