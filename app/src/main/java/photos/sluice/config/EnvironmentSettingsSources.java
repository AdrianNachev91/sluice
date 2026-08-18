package photos.sluice.config;

import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.SystemEnvironmentOrigin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.SettingsSources;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the running app's own property sources to answer what outranks the user's config file.
 *
 * <p>The boundary is positional rather than a list of source names. Spring loads every config file
 * as one kind of source, and the user's own is the highest-precedence one of that kind. So
 * everything above the first of them is above the config file. That covers an exported variable, a
 * command-line argument, and whatever a future launcher puts there.
 *
 * <p>A machine with no config file at all lands on the bundled defaults instead. That is the same
 * answer: nothing the user typed is being outranked, and everything above still wins at the next
 * launch.
 *
 * <p>Enumerating source names instead would answer correctly today, and stop the moment anything
 * new sat above the file.
 */
@Component
public class EnvironmentSettingsSources implements SettingsSources {

    private final ConfigurableEnvironment environment;

    /**
     * Creates the reader over the running app's environment.
     *
     * @param environment {@link ConfigurableEnvironment} the app's own property sources, in
     *         precedence order
     */
    public EnvironmentSettingsSources(final ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /**
     * What supplies the given property from above the user's config file.
     *
     * @param property {@link String} the property name, as the app spells it in its own config file
     * @return an {@link Optional} of {@link SettingOverride} what supplies it from above
     */
    @Override
    public Optional<SettingOverride> overriddenAboveTheConfigFile(final String property) {
        return this.sourcesAboveTheConfigFile().stream()
                .filter(source -> source.containsProperty(property))
                .findFirst()
                .map(source -> describe(source, property));
    }

    /**
     * Every source that outranks the user's config file, in precedence order.
     *
     * @return a {@link List} of {@link PropertySource} the sources above the config file
     */
    private List<PropertySource<?>> sourcesAboveTheConfigFile() {
        final List<PropertySource<?>> above = new ArrayList<>();
        for (final PropertySource<?> source : this.environment.getPropertySources()) {
            if (source instanceof OriginTrackedMapPropertySource) {
                return above;
            }
            // Skipped rather than counted. Spring attaches one source at the very top that answers
            // for every property in every other source. Counted, it would report every setting the
            // app has as overridden, the config file's own included.
            if (!ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                above.add(source);
            }
        }
        // No config file was loaded at all, which a bare context reaches and the app does not.
        // Nothing outranks a file that is not there, so nothing is reported.
        return List.of();
    }

    /**
     * Names what a source is, for a sentence a user reads.
     *
     * <p>Asks the source itself rather than deriving the name. Several spellings of a variable
     * resolve to one property through relaxed binding, and only the source knows which one is set.
     *
     * @param source a {@link PropertySource} of ? the source supplying the property
     * @param property {@link String} the property it supplies
     * @return {@link SettingOverride} what to tell the user supplies it
     */
    private static SettingOverride describe(final PropertySource<?> source, final String property) {
        if (OriginLookup.getOrigin(source, property) instanceof final SystemEnvironmentOrigin variable) {
            return new SettingOverride.ByEnvironmentVariable(property, variable.getProperty());
        }
        return new SettingOverride.ByAnotherSource(property, whatThatSourceIs(source.getName()));
    }

    /**
     * What to call a source in a sentence, given the name Spring files it under.
     *
     * <p>Those names are Spring's own identifiers, and a user has never seen one. Rendered as
     * written they reach the screen as the first word of a sentence: "commandLineArgs outranks what
     * is saved here." Anything this method does not recognise is described by where it stands
     * rather than named, since an unrecognised name is exactly the one worth not printing.
     *
     * @param name {@link String} the property source's own name
     * @return {@link String} what to call it, as a sentence starts
     */
    private static String whatThatSourceIs(final String name) {
        return switch (name) {
            case "commandLineArgs" -> "A command-line argument";
            case "systemProperties" -> "A Java system property";
            case "systemEnvironment" -> "An environment variable";
            default -> "Something outside your settings file";
        };
    }
}
