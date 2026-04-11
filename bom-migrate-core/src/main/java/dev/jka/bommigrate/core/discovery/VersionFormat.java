package dev.jka.bommigrate.core.discovery;

/**
 * How the {@link BomGenerator} should emit version values in the generated
 * {@code <dependencyManagement>} entries.
 */
public enum VersionFormat {

    /**
     * Versions are inlined directly on the {@code <dependency>} elements.
     * <pre>
     * &lt;dependency&gt;
     *   &lt;groupId&gt;com.google.guava&lt;/groupId&gt;
     *   &lt;artifactId&gt;guava&lt;/artifactId&gt;
     *   &lt;version&gt;33.0.0-jre&lt;/version&gt;
     * &lt;/dependency&gt;
     * </pre>
     */
    INLINE,

    /**
     * Versions are extracted into a {@code <properties>} block and referenced
     * via {@code ${artifactId.version}}. If two candidates share the same
     * artifactId (rare), the second is qualified with its groupId.
     * <pre>
     * &lt;properties&gt;
     *   &lt;guava.version&gt;33.0.0-jre&lt;/guava.version&gt;
     * &lt;/properties&gt;
     * &lt;dependency&gt;
     *   &lt;groupId&gt;com.google.guava&lt;/groupId&gt;
     *   &lt;artifactId&gt;guava&lt;/artifactId&gt;
     *   &lt;version&gt;${guava.version}&lt;/version&gt;
     * &lt;/dependency&gt;
     * </pre>
     */
    PROPERTIES
}
