package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Opens the desktop window.
 *
 * <p>Declared here so the help lists it and the parser knows the name, not because this class opens
 * anything. The window is launched before this surface exists. A command line that loaded a window
 * toolkit would start threads a one-shot run has no use for, and a machine with no desktop has no
 * toolkit to load at all.
 *
 * <p>So the only invocation that reaches this class is one carrying something besides the verb,
 * which is what it refuses. The window takes no settings of its own from a command line.
 */
@Component
@Profile("cli")
@Command(name = "app", description = "Open the Sluice desktop application.")
public class AppCommand implements Callable<Integer> {

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    /**
     * Refuses the invocation that got here.
     *
     * @return {@link Integer} never returns, the refusal leaves through the parser's own handler
     * @throws ParameterException always, since reaching this class means the verb carried arguments
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        throw new ParameterException(running.commandLine(),
                "app takes no arguments. Run 'sluice app' on its own to open the desktop application.");
    }
}
