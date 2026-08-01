package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.domain.job.WatchMode;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Binds the {@code sluice.cull} settings: which provider drives the culler, that provider's own
 * settings, the classification categories it routes photos to, and the external-agent watch mode.
 *
 * <p>Implements {@link CullSettings} directly, so the application layer can select and configure a
 * culler without importing this config record.
 */
@ConfigurationProperties(prefix = "sluice.cull")
public record CullConfig(String provider, CullProviderSettings providerSettings, List<CullCategory> categories,
                         ExternalAgentSettings externalAgent) implements CullSettings {

    // provider() is supplied by the record's own accessor, satisfying CullSettings so the application
    // layer selects a culler without importing this config record.

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
        // Spring can bind null here when the property is absent or written with no value; the IDE
        // can't model that reflective path and reads the guards as always-false.
        //noinspection ConstantValue
        categories = categories == null ? List.of() : List.copyOf(categories);
        // The port promises a never-null settings object whose fields are null when unset, so an
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
        // Two cards sharing a name would silently alias one category, so duplicates fail loud.
        final List<String> duplicates = categories.stream()
                .collect(Collectors.groupingBy(CullCategory::name, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cull categories contain duplicate name(s): " + String.join(", ", duplicates));
        }
    }
}
