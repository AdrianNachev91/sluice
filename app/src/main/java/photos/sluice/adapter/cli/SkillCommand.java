package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Prints the instructions an agent needs to drive this surface.
 *
 * <p>Printed rather than written anywhere. Installing it into somebody's agent configuration
 * uninvited is not this app's to do, so the caller decides where it lands.
 *
 * <p>It reads nothing but its own bundled text, so it needs no configured folders and claims no
 * working root. An install with none of its folders set yet can therefore still be asked how to set
 * them, which is the state those instructions exist for.
 */
@Component
@Profile("cli")
@Command(name = SkillCommand.VERB,
        description = "Print the agent instructions for driving Sluice, ready to save as a skill file.")
public class SkillCommand implements Callable<Integer> {

    /**
     * The name this command is typed as.
     */
    static final String VERB = "skill";

    /**
     * Where the text ships inside the jar.
     */
    private static final String SKILL_RESOURCE = "/cli/skill.md";

    /**
     * Stands in the bundled text for whichever build is printing it.
     */
    private static final String VERSION_PLACEHOLDER = "{{version}}";

    private final CommandReports reports;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    /**
     * Creates the command.
     *
     * @param reports {@link CommandReports} writes whatever this produced
     */
    public SkillCommand(final CommandReports reports) {
        this.reports = reports;
    }

    /**
     * Prints the skill.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, VERB, () -> outcomeOf(skillText()));
    }

    /**
     * The bundled text with this build's own name in it.
     *
     * @return {@link String} the text to print
     */
    static String skillText() {
        return load().replace(VERSION_PLACEHOLDER, new SluiceVersionProvider().getVersion()[0]);
    }

    /**
     * What the command makes of the text it loaded.
     *
     * @param skill {@link String} the text
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome outcomeOf(final String skill) {
        return CommandOutcome.done(Fields.of("skill", skill), List.of(skill.split("\n", -1)));
    }

    /**
     * The text as it ships.
     *
     * <p>A missing or unreadable resource is a packaging defect rather than anything a caller did,
     * so it is raised rather than reported as a refusal.
     *
     * @return {@link String} the bundled text
     */
    private static String load() {
        try (final var input = SkillCommand.class.getResourceAsStream(SKILL_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Bundled agent instructions " + SKILL_RESOURCE + " are missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (final IOException e) {
            throw new IllegalStateException("Bundled agent instructions " + SKILL_RESOURCE
                    + " could not be read", e);
        }
    }
}
