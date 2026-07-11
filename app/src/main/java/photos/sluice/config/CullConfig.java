package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sluice.cull")
public record CullConfig(String provider, ProviderSettings providerSettings) {

    public record ProviderSettings(String model, String endpoint) {
    }
}
