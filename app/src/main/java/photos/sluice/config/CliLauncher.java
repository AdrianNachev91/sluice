package photos.sluice.config;

import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import photos.sluice.SluiceApplication;
import photos.sluice.adapter.cli.SluiceCli;
import picocli.CommandLine.IFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Runs one command and answers with its exit code. This lives in the wiring layer because it names
 * a concrete command class. Naming a concrete effect implementation is the one thing only the
 * wiring layer may do.
 *
 * <p>The desktop toolkit never loads here. The branch that chooses between the two launchers sits
 * ahead of anything toolkit-shaped, and every desktop bean is excluded from this profile, so none
 * is ever constructed.
 *
 * <p>Nothing gives the working root back on the way out. A one-shot process holds what it claimed
 * for its whole life, and the kernel drops the claim when the process ends.
 */
public final class CliLauncher {

    private static final String CONFIG_IMPORT_ARG = "--spring.config.import=optional:file:";

    /**
     * Where the parser stops treating arguments as options, and so where this class stops filtering.
     */
    private static final String END_OF_OPTIONS = "--";

    /**
     * Spring's own switches that carry no dotted name, and would otherwise reach the parser as
     * mistyped flags.
     */
    private static final Set<String> SPRING_SWITCHES = Set.of("--debug", "--trace");

    /**
     * The profile the desktop's own beans are excluded from, and the command line's are gated to.
     */
    private static final String CLI_PROFILE = "cli";

    /**
     * Prevents instantiation of this static utility class.
     */
    private CliLauncher() {
    }

    /**
     * Runs the command the arguments name.
     *
     * @param configFile {@link Path} the user's config file, which need not exist
     * @param args {@link String}[] the command-line arguments
     * @return int the exit code to leave the process with
     */
    public static int run(final Path configFile, final String[] args) {
        try (final ConfigurableApplicationContext context = new SpringApplicationBuilder(SluiceApplication.class)
                .profiles(CLI_PROFILE)
                // Both would otherwise write to the output stream, which carries the command's
                // result. Log output is kept off that stream by logback-spring.xml, which sends
                // this profile's to the error stream. Neither of these two is log output, so each
                // is switched off here instead.
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(springArgs(configFile, args))) {
            return SluiceCli.parser(context.getBean(SluiceCli.class), context.getBean(IFactory.class))
                    .execute(commandArgs(args));
        }
    }

    /**
     * Builds the argument list Spring starts with: the config-file import first, then everything
     * the user passed. The user's arguments come last so an explicitly passed property still wins.
     *
     * <p>Everything is passed on, the parser's own flags included. A flag Spring does not
     * recognise becomes a property nothing reads, which costs nothing. Filtering instead would mean
     * this class knowing the whole command surface.
     *
     * @param configFile {@link Path} the user's config file, which need not exist
     * @param args {@link String}[] the command-line arguments
     * @return {@link String}[] the arguments to start Spring with
     */
    static String[] springArgs(final Path configFile, final String[] args) {
        return Stream.concat(Stream.of(CONFIG_IMPORT_ARG + configFile), Stream.of(args))
                .toArray(String[]::new);
    }

    /**
     * Builds the argument list the parser sees: everything except what belongs to Spring.
     *
     * <p>A setting is overridden for one run by naming it the way the config file spells it, as in
     * {@code --sluice.paths.inbox=D:\Photos}. That is Spring's own syntax and it is what points a
     * run at a different folder without editing anything. The parser would read it as a mistyped
     * flag, so it never reaches the parser.
     *
     * <p>Telling the two apart is the dot, plus Spring's two dotless switches by name. Every
     * setting is a dotted name, and a flag on this surface may not contain one.
     * {@code SluiceCliTest} holds that convention to the registered option names, so a later verb
     * cannot quietly break it. A mistyped flag is still refused, which is the half a blanket
     * "ignore what you do not recognise" would have thrown away.
     *
     * <p>Nothing is filtered after {@code --}. What follows it is a value the user is insisting on
     * rather than an option. So a value that happens to look like a dotted flag reaches the command
     * intact.
     *
     * @param args {@link String}[] the command-line arguments
     * @return {@link String}[] the arguments to parse
     */
    static String[] commandArgs(final String[] args) {
        final List<String> parsed = new ArrayList<>();
        boolean valuesOnly = false;
        for (final String arg : args) {
            if (valuesOnly || !belongsToSpring(arg)) {
                parsed.add(arg);
            }
            valuesOnly = valuesOnly || END_OF_OPTIONS.equals(arg);
        }
        return parsed.toArray(String[]::new);
    }

    /**
     * Says whether an argument is Spring's rather than the parser's.
     *
     * @param arg {@link String} one command-line argument
     * @return boolean true when Spring alone should see it
     */
    private static boolean belongsToSpring(final String arg) {
        if (!arg.startsWith("--")) {
            return false;
        }
        final int assignment = arg.indexOf('=');
        final String name = assignment < 0 ? arg : arg.substring(0, assignment);
        return SPRING_SWITCHES.contains(name) || name.indexOf('.') >= 0;
    }
}
