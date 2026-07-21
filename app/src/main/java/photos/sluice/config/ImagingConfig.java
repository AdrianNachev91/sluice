package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sluice.imaging")
public record ImagingConfig(String heifDecoderCommand) {
}
