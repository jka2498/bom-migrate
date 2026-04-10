package dev.jka.bommigrate.core.discovery;

/**
 * Severity of version conflicts for a BOM candidate dependency.
 */
public enum ConflictSeverity {
    /** All services use the same version. */
    NONE,
    /** Services use different minor/patch versions (e.g. 2.0.1 vs 2.0.3). */
    MINOR,
    /** Services use different major versions (e.g. 1.x vs 2.x). */
    MAJOR
}
