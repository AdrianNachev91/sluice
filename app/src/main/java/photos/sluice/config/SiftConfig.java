package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import photos.sluice.application.port.out.SiftProviderSettings;
import photos.sluice.domain.sift.SiftCategory;

import java.util.List;
import java.util.Map;

/**
 * Binds the {@code sluice.sift} settings. Which provider drives the sieve, the settings kept for
 * each provider, and the classification categories it routes photos to.
 *
 * <p>These are the values the app starts on. {@link SettingsHolder} takes them from here once and
 * is what everything reads afterwards. So a save changes what a sift sees without a restart.
 */
@ConfigurationProperties(prefix = "sluice.sift")
public record SiftConfig(String provider, Map<String, SiftProviderSettings> providerSettings,
                         List<SiftCategory> categories) {

    /**
     * categories are the classification cards the sieve routes to (junk/scenery/food/funny by
     * default, extensible by the user). Each card binds directly to the port's SiftCategory record,
     * which enforces its own non-blank invariants. The defaults live in application.yml, this
     * project's convention for config defaults - the compact constructor only makes the bound list
     * immutable and null-safe.
     *
     * @param provider {@link String} the selected sift provider name
     * @param providerSettings a {@link Map} of {@link String} to {@link SiftProviderSettings}, the
     *     connection settings configured for each provider, keyed by provider id
     * @param categories a {@link List} of {@link SiftCategory} classification cards the sieve routes to
     */
    public SiftConfig {
        // Spring can bind null here when the property is absent or written with no value. The IDE
        // can't model that reflective path, so it reads the guards as always-false.
        //noinspection ConstantValue
        categories = categories == null ? List.of() : List.copyOf(categories);
        // A provider with no block of its own reads every setting as unset, so an absent
        // provider-settings node is the same thing as an empty one.
        //noinspection ConstantValue
        providerSettings = providerSettings == null ? Map.of() : Map.copyOf(providerSettings);
    }
}
