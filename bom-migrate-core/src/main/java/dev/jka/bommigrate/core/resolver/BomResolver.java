package dev.jka.bommigrate.core.resolver;

import dev.jka.bommigrate.core.model.DependencyManagementMap;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Resolves a Maven BOM into a {@link DependencyManagementMap} containing
 * all managed dependency versions with properties fully interpolated.
 */
public interface BomResolver {

    /**
     * Resolves the given BOM POM and returns a map of all managed dependencies.
     *
     * @param bomPath                      path to the BOM's pom.xml (or its parent directory)
     * @param includeTransitiveBomImports  if true, also resolves BOMs imported via
     *                                     {@code <scope>import</scope>} in the BOM's
     *                                     own dependencyManagement
     * @return resolved dependency management map
     * @throws IOException on parse or file errors
     */
    DependencyManagementMap resolve(Path bomPath, boolean includeTransitiveBomImports) throws IOException;
}
