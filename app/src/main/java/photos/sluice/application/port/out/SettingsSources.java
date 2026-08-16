package photos.sluice.application.port.out;

import java.util.Optional;

/**
 * Where the app's settings are actually coming from, as against where a save writes them.
 *
 * <p>Configuration precedence puts environment variables and command-line arguments above the
 * user's config file. A save writes that file and puts the value in force for the rest of the
 * session, and the next launch reads the higher source again. Without this, that revert has nothing
 * on screen explaining it.
 *
 * <p>A port rather than a method on the class that reads the property sources. Only the wiring
 * layer may name that class, which is why every other seam here is a port too.
 */
public interface SettingsSources {

    /**
     * What supplies the given property from above the user's config file, or empty when nothing
     * does and a saved value will hold.
     *
     * <p>Answers for one property at a time, because the caller is a screen with a fixed set of
     * fields and knows which property each of them writes. Reading every setting the app has is a
     * different question, and nothing asks it.
     *
     * @param property {@link String} the property name, as the app spells it in its own config file
     * @return an {@link Optional} of {@link SettingOverride} what supplies it from above
     */
    Optional<SettingOverride> overriddenAboveTheConfigFile(String property);
}
