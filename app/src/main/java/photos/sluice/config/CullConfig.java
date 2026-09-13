package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.domain.cull.CullCategory;

import java.util.List;
import java.util.Map;

/**
 * Binds the {@code sluice.sift} settings. Which provider drives the culler, the settings kept for
 * each provider, and the classification categories it routes photos to.
 *
 * <p>These are the values the app starts on. {@link SettingsHolder} takes them from here once and
 * is what everything reads afterwards. So a save changes what a cull sees without a restart.
 */
@ConfigurationProperties(prefix = "sluice.sift")
public record CullConfig(String provider, Map<String, CullProviderSettings> providerSettings,
                         List<CullCategory> categories) {

    /**
     * categories are the classification cards the culler routes to (junk/scenery/food/funny by
     * default, extensible by the user). Each card binds directly to the port's CullCategory record,
     * which enforces its own non-blank invariants. The defaults live in application.yml, this
     * project's convention for config defaults - the compact constructor only makes the bound list
     * immutable and null-safe.
     *
     * @param provider {@link String} the selected cull provider name
     * @param providerSettings a {@link Map} of {@link String} to {@link CullProviderSettings}, the
     *     connection settings configured for each provider, keyed by provider id
     * @param categories a {@link List} of {@link CullCategory} classification cards the culler routes to
     */
    public CullConfig {
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
