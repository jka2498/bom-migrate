package dev.jka.bommigrate.core.discovery;

/**
 * A single user-confirmed candidate assigned to a specific BOM module with a
 * confirmed version. Produced by the interactive review wizard or the web UI.
 *
 * @param candidate         the underlying discovery candidate
 * @param module            the BOM module the user assigned this candidate to
 * @param confirmedVersion  the version to include in the BOM (may differ from the suggested version)
 */
public record BomModuleAssignment(
        BomCandidate candidate,
        BomModule module,
        String confirmedVersion
) {

    public BomModuleAssignment {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        if (module == null) {
            throw new IllegalArgumentException("module must not be null");
        }
        if (confirmedVersion == null || confirmedVersion.isBlank()) {
            throw new IllegalArgumentException("confirmedVersion must not be blank");
        }
    }
}
