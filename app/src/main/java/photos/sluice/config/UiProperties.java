package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import photos.sluice.application.port.out.ThemeChoice;

/**
 * Binds the {@code sluice.ui} settings: which look the app wears.
 *
 * <p>Its own prefix rather than a field on an existing one, because none of the four describe the
 * app's appearance. {@code paths} is where files live, {@code sift} and {@code montage} are how a
 * run behaves, and {@code imaging} is an external decoder.
 */
@ConfigurationProperties(prefix = "sluice.ui")
public record UiProperties(ThemeChoice theme) {

    /**
     * Defaults the look to {@link ThemeChoice#SYSTEM} when unset.
     *
     * @param theme {@link ThemeChoice} the look the user asked for
     */
    public UiProperties {
        // Spring binds null here when the property is absent, and the IDE cannot model that
        // reflective path.
        //noinspection ConstantValue
        if (theme == null) {
            theme = ThemeChoice.SYSTEM;
        }
    }
}
