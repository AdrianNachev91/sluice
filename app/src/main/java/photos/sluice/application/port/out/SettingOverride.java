package photos.sluice.application.port.out;

/**
 * A setting whose value comes from somewhere that outranks the user's config file, so what a save
 * writes is not what the next launch will run on.
 *
 * <p>Two variants because a screen says different things for them. An exported variable is
 * something the user can go and unset. A value passed on the command line belongs to however this
 * copy of the app was started, and naming it is all a screen can do.
 */
public sealed interface SettingOverride {

    /**
     * The property this override supplies.
     *
     * @return {@link String} the property name, as the app spells it
     */
    String property();

    /**
     * An operating-system environment variable supplies the value.
     *
     * @param property {@link String} the property name, as the app spells it
     * @param variableName {@link String} the variable's own name, as the environment spells it
     */
    record ByEnvironmentVariable(String property, String variableName) implements SettingOverride {
    }

    /**
     * Something above the config file other than an environment variable supplies the value, such
     * as a command-line argument.
     *
     * @param property {@link String} the property name, as the app spells it
     * @param source {@link String} what supplies it, for display and never for branching on
     */
    record ByAnotherSource(String property, String source) implements SettingOverride {
    }
}
