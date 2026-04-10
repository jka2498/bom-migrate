package dev.jka.bommigrate.core.model;

/**
 * Classification of a dependency during BOM migration.
 */
public enum MigrationAction {

    /** Version matches BOM exactly — safe to strip the version tag. */
    STRIP,

    /** Version is in the BOM but differs — flag for human review. */
    FLAG,

    /** Dependency not managed by the BOM, or otherwise untouchable — leave as-is. */
    SKIP
}
