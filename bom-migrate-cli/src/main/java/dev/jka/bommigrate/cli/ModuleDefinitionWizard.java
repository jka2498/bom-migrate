package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.discovery.BomModule;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompts the user interactively for BOM module names when {@code --bom-modules}
 * was not provided on the command line.
 */
public final class ModuleDefinitionWizard {

    private final BufferedReader input;
    private final PrintStream output;

    public ModuleDefinitionWizard() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
    }

    /** Package-private constructor for testing. */
    ModuleDefinitionWizard(BufferedReader input, PrintStream output) {
        this.input = input;
        this.output = output;
    }

    /**
     * Parses a comma-separated module list string (e.g. from the {@code --bom-modules} flag).
     * Returns a list with the default module if the input is null or blank.
     */
    public static List<BomModule> parseModuleList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of(BomModule.defaultModule());
        }
        List<BomModule> modules = new ArrayList<>();
        for (String name : csv.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                modules.add(new BomModule(trimmed));
            }
        }
        return modules.isEmpty() ? List.of(BomModule.defaultModule()) : modules;
    }

    /**
     * Prompts the user to enter module names. Empty input creates a single
     * {@code default} module.
     */
    public List<BomModule> promptForModules() throws IOException {
        output.println("No BOM modules defined. Enter module names (comma-separated), or press Enter for a single unnamed BOM:");
        output.print("> ");
        String line = input.readLine();
        return parseModuleList(line);
    }
}
