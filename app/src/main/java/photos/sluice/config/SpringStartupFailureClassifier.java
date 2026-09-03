package photos.sluice.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginProvider;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import photos.sluice.application.port.out.UnusableSettingsException;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.startup.StartupFailure;
import photos.sluice.application.startup.StartupFailure.ConfigPosition;
import photos.sluice.application.startup.StartupFailure.ConfigSpot;
import photos.sluice.application.startup.StartupFailure.RejectedSetting;
import photos.sluice.application.startup.StartupFailure.Unclassified;
import photos.sluice.application.startup.StartupFailure.UnparsableConfigFile;
import photos.sluice.application.startup.StartupFailure.UnusableSettings;
import photos.sluice.application.startup.StartupFailure.WorkingRootBusy;
import photos.sluice.application.startup.StartupFailureClassifier;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

/**
 * Reads what the framework raised and says which failure it was. This lives in the wiring layer
 * because it is the only place allowed to know both the framework's exception shapes and the value
 * every surface renders.
 *
 * <p>It searches the cause chain rather than reading the type it was handed. The exception that
 * says a setting would not bind was measured four wrappers down. What the search consults is this
 * app's own list of failures it has copy for. A failure nobody wrote a branch for takes the
 * unclassified answer by construction.
 *
 * <p>The search runs from the outside in and stops at the first type on that list, so a failure
 * that wraps another is read as the outer one.
 */
public class SpringStartupFailureClassifier implements StartupFailureClassifier {

    // Bounds both walks below, the causes and the origins. The deepest chain measured here was
    // seven, so a real failure reaches its own end long first. What the bound buys is termination
    // on a chain that loops back on itself. A loop would otherwise hang the one window whose job
    // is to explain why nothing else works.
    private static final int MAX_CHAIN_DEPTH = 100;

    private final Path configFile;

    /**
     * Creates the classifier over the config file this process was started with.
     *
     * @param configFile {@link Path} the user's config file, which need not exist
     */
    public SpringStartupFailureClassifier(final Path configFile) {
        this.configFile = configFile;
    }

    /**
     * Says what kind of failure this is.
     *
     * @param failure {@link Throwable} what was raised while starting
     * @return {@link StartupFailure} the classified failure
     */
    @Override
    public StartupFailure classify(final Throwable failure) {
        final String trace = render(failure);
        Throwable current = failure;
        int remaining = MAX_CHAIN_DEPTH;
        while (current != null && remaining > 0) {
            final StartupFailure known = this.branchFor(current, trace);
            if (known != null) {
                return known;
            }
            current = current.getCause();
            remaining--;
        }
        return new Unclassified(trace);
    }

    /**
     * The text resource a value's origin points at, or null when it points at something with no
     * text behind it.
     *
     * <p>A bound value's origin names the property source it came from and carries the origin
     * underneath it, so the one worth reading is a wrapper or two down.
     *
     * <p>Package-private so a test can hand it an origin that holds itself. Nothing the framework
     * builds is reachable to construct one, and a walk that spun here would hang the one window
     * whose job is to explain why nothing else works.
     *
     * @param origin {@link Origin} the origin a bound value carries
     * @return {@link TextResourceOrigin} the text resource it names, or null when it names none
     */
    static @Nullable TextResourceOrigin textOrigin(final @Nullable Origin origin) {
        Origin current = origin;
        int remaining = MAX_CHAIN_DEPTH;
        while (current != null && remaining > 0) {
            if (current instanceof final TextResourceOrigin text) {
                return text;
            }
            // An origin can hold the one underneath it either way round, and holding it one way
            // does not mean it holds nothing the other. Taking only the first would drop a parent
            // whenever a provider answers with nothing.
            final Origin wrapped = current instanceof final OriginProvider provider ? provider.getOrigin() : null;
            current = wrapped == null ? current.getParent() : wrapped;
            remaining--;
        }
        return null;
    }

    /**
     * The branch one exception in the chain answers to, or null when this app has no copy for it.
     *
     * @param failure {@link Throwable} one exception from the chain
     * @param trace {@link String} the whole chain's rendered stack trace
     * @return {@link StartupFailure} the failure this one names, or null when it names none
     */
    private @Nullable StartupFailure branchFor(final Throwable failure, final String trace) {
        return switch (failure) {
            case final WorkingRootBusyException busy -> new WorkingRootBusy(busy.workingRoot(), trace);
            case final BindException bind -> new RejectedSetting(bind.getName().toString(),
                    this.spotOf(bind), trace);
            case final MarkedYAMLException yaml -> this.unparsable(yaml, trace);
            case final UnusableSettingsException refused -> new UnusableSettings(refused.getMessage(), trace);
            default -> null;
        };
    }

    /**
     * Where in the user's config file a rejected value sits, or null when it came from somewhere
     * else. A value the user passed on the command line or set in the environment is not in that
     * file. Saying it is would send them to edit a line that holds something else.
     *
     * @param bind {@link BindException} the framework's report that a setting would not bind
     * @return {@link ConfigSpot} where the value sits, or null when it is not in the config file
     */
    private @Nullable ConfigSpot spotOf(final BindException bind) {
        final ConfigurationProperty property = bind.getProperty();
        if (property == null) {
            return null;
        }
        final TextResourceOrigin origin = textOrigin(property.getOrigin());
        if (origin == null) {
            return null;
        }
        final Path source = fileOf(origin.getResource());
        if (source == null || !this.isTheConfigFile(source)) {
            return null;
        }
        return new ConfigSpot(source, positionOf(origin));
    }

    /**
     * The classified answer for a config file the parser gave up on.
     *
     * <p>The file comes from this process's own launch rather than from the parser. Spring hands
     * the file over as a stream, so what the parser knows it is reading is called {@code reader}.
     *
     * @param yaml {@link MarkedYAMLException} what the parser raised
     * @param trace {@link String} the whole chain's rendered stack trace
     * @return {@link StartupFailure} the unparsable-file failure
     */
    private StartupFailure unparsable(final MarkedYAMLException yaml, final String trace) {
        final Mark mark = yaml.getProblemMark();
        final ConfigPosition position = mark == null
                ? null
                : new ConfigPosition(mark.getLine() + 1, mark.getColumn() + 1);
        return new UnparsableConfigFile(new ConfigSpot(this.configFile, position), yaml.getProblem(), trace);
    }

    /**
     * Whether a file is the one this process was started with. A value bound from the app's own
     * bundled defaults can come from a real file too, and that one is not the user's to edit.
     *
     * @param candidate {@link Path} the file a value came from
     * @return boolean true when it is the user's own config file
     */
    private boolean isTheConfigFile(final Path candidate) {
        return candidate.toAbsolutePath().normalize().equals(this.configFile.toAbsolutePath().normalize());
    }

    /**
     * The line and column a text origin names, counted from one. Both are counted from zero at the
     * source, and an editor counts from one.
     *
     * @param origin {@link TextResourceOrigin} the origin of a value read from a text resource
     * @return {@link ConfigPosition} where the value sits, or null when the origin says only which
     *     resource it was
     */
    private static @Nullable ConfigPosition positionOf(final TextResourceOrigin origin) {
        final TextResourceOrigin.Location location = origin.getLocation();
        return location == null ? null : new ConfigPosition(location.getLine() + 1, location.getColumn() + 1);
    }

    /**
     * The file behind a resource, or null when the resource is not one on this filesystem.
     *
     * @param resource {@link Resource} the resource a value was read from
     * @return {@link Path} the file, or null when there is none
     */
    private static @Nullable Path fileOf(final @Nullable Resource resource) {
        if (resource == null) {
            return null;
        }
        try {
            return resource.getFile().toPath();
        } catch (final IOException notAFile) {
            return null;
        }
    }

    /**
     * The failure's stack trace, rendered whole. The chain is what a report needs, so nothing is
     * trimmed here.
     *
     * @param failure {@link Throwable} the failure to render
     * @return {@link String} the rendered trace
     */
    private static String render(final Throwable failure) {
        final var text = new StringWriter();
        try (final var writer = new PrintWriter(text)) {
            failure.printStackTrace(writer);
        }
        return text.toString();
    }
}
