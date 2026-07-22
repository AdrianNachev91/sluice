package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import photos.sluice.application.port.out.CullSettings;

import java.util.List;

@ConfigurationProperties(prefix = "sluice.cull")
public record CullConfig(String provider, ProviderSettings providerSettings, List<String> categories)
        implements CullSettings {

    // provider() is supplied by the record's own accessor, satisfying CullSettings so the application
    // layer selects a culler without importing this config record.

    // categories is the classification set the culler routes to (junk/scenery/food/funny by default,
    // extensible by the user); ShardValidator checks every classification against it. The default
    // lives in application.yml, this project's convention for config defaults - the compact
    // constructor only makes the bound list immutable and null-safe.
    public CullConfig {
        // Spring can bind null here when the property is absent or written with no value; the IDE
        // can't model that reflective path and reads the guard as always-false.
        //noinspection ConstantValue
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public record ProviderSettings(String model, String endpoint) {
    }
}
