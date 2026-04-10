package dev.jka.bommigrate.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Root command for bom-migrate. Dispatches to one of two subcommands:
 * <ul>
 *   <li>{@code migrate} — remove hardcoded {@code <version>} tags managed by a BOM (V1 behaviour)</li>
 *   <li>{@code discover} — scan services and produce BOM candidates (V2 feature)</li>
 * </ul>
 *
 * <p>For backward compatibility, invoking with a leading flag (e.g.
 * {@code bom-migrate --bom X --target Y}) is rewritten to prepend {@code migrate}
 * so existing V1 scripts continue to work.
 */
@Command(
        name = "bom-migrate",
        mixinStandardHelpOptions = true,
        version = "bom-migrate 1.2.0-SNAPSHOT",
        description = "Maven BOM migration and discovery tool.",
        subcommands = {
                MigrateCommand.class,
                DiscoverCommand.class
        }
)
public class BomMigrateCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    public static void main(String[] args) {
        // Legacy fallback: if the first arg is a flag (not a subcommand), prepend "migrate"
        // so `bom-migrate --bom X --target Y` keeps working the way it did in V1.
        if (args.length > 0 && args[0].startsWith("-") && !isHelpOrVersion(args[0])) {
            String[] newArgs = new String[args.length + 1];
            newArgs[0] = "migrate";
            System.arraycopy(args, 0, newArgs, 1, args.length);
            args = newArgs;
        }
        int exitCode = new CommandLine(new BomMigrateCommand()).execute(args);
        System.exit(exitCode);
    }

    private static boolean isHelpOrVersion(String arg) {
        return arg.equals("-h") || arg.equals("--help") || arg.equals("-V") || arg.equals("--version");
    }
}
