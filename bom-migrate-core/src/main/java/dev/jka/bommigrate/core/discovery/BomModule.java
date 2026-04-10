package dev.jka.bommigrate.core.discovery;

/**
 * A named module within a generated multi-module BOM.
 *
 * @param name the module name (used as the artifactId suffix and directory name)
 */
public record BomModule(String name) {

    public BomModule {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("module name must not be blank");
        }
    }

    /** The default module used when the user provides no module names. */
    public static BomModule defaultModule() {
        return new BomModule("default");
    }
}
