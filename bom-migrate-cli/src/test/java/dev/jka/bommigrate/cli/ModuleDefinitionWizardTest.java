package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.discovery.BomModule;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleDefinitionWizardTest {

    @Test
    void parseNullReturnsDefault() {
        List<BomModule> modules = ModuleDefinitionWizard.parseModuleList(null);
        assertThat(modules).hasSize(1).extracting(BomModule::name).containsExactly("default");
    }

    @Test
    void parseBlankReturnsDefault() {
        List<BomModule> modules = ModuleDefinitionWizard.parseModuleList("   ");
        assertThat(modules).hasSize(1).extracting(BomModule::name).containsExactly("default");
    }

    @Test
    void parseSingleModule() {
        List<BomModule> modules = ModuleDefinitionWizard.parseModuleList("backend-core");
        assertThat(modules).hasSize(1).extracting(BomModule::name).containsExactly("backend-core");
    }

    @Test
    void parseMultipleModulesWithSpaces() {
        List<BomModule> modules = ModuleDefinitionWizard.parseModuleList("backend-core, misc ,frontend");
        assertThat(modules).extracting(BomModule::name)
                .containsExactly("backend-core", "misc", "frontend");
    }

    @Test
    void promptForModulesEmptyInputReturnsDefault() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("\n"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(out, true, StandardCharsets.UTF_8);

        ModuleDefinitionWizard wizard = new ModuleDefinitionWizard(input, output);
        List<BomModule> modules = wizard.promptForModules();

        assertThat(modules).hasSize(1).extracting(BomModule::name).containsExactly("default");
    }

    @Test
    void promptForModulesCustomInput() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("core, misc\n"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(out, true, StandardCharsets.UTF_8);

        ModuleDefinitionWizard wizard = new ModuleDefinitionWizard(input, output);
        List<BomModule> modules = wizard.promptForModules();

        assertThat(modules).extracting(BomModule::name).containsExactly("core", "misc");
    }
}
