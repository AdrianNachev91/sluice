package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.job.WatchMode;

import java.util.List;

/**
 * Binds the {@code sluice.cull} settings: which provider drives the culler, that provider's own
 * settings, the classification categories it routes photos to, and the external-agent watch mode.
 *
 * <p>These are the values the app starts on. {@link SettingsHolder} takes them from here once and
 * is what everything reads afterwards. So a save changes what a cull sees without a restart.
 */
@ConfigurationProperties(prefix = "sluice.cull")
public record CullConfig(String provider, CullProviderSettings providerSettings, List<CullCategory> categories,
                         ExternalAgentSettings externalAgent) {

    /**
     * categories are the classification cards the culler routes to (junk/scenery/food/funny by
     * default, extensible by the user). Each card binds directly to the port's CullCategory record,
     * which enforces its own non-blank invariants. The defaults live in application.yml, this
     * project's convention for config defaults - the compact constructor only makes the bound list
     * immutable and null-safe.
     *
     * @param provider {@link String} the selected cull provider name
     * @param providerSettings {@link CullProviderSettings} provider-specific settings
     * @param categories a {@link List} of {@link CullCategory} classification cards the culler routes to
     * @param externalAgent {@link ExternalAgentSettings} external-agent watch mode settings
     */
    public CullConfig {
        // Spring can bind null here when the property is absent or written with no value. The IDE
        // can't model that reflective path, so it reads the guards as always-false.
        //noinspection ConstantValue
        categories = categories == null ? List.of() : List.copyOf(categories);
        // The port requires a non-null settings object whose fields are null when unset, so an
        // absent provider-settings node normalizes to that shape here.
        //noinspection ConstantValue
        if (providerSettings == null) {
            providerSettings = new CullProviderSettings(null, null, null, null);
        }
        // Same absent-node normalization as providerSettings above - a user who never touches
        // sluice.cull.external-agent (every non-external-agent provider) gets the MANUAL default
        // via ExternalAgentSettings' own compact constructor.
        //noinspection ConstantValue
        if (externalAgent == null) {
            externalAgent = new ExternalAgentSettings(WatchMode.MANUAL);
        }
    }
}
