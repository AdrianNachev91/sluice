package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code sluice.imaging} settings: the external command line used to decode HEIF and HEIC
 * images.
 */
@ConfigurationProperties(prefix = "sluice.imaging")
public record ImagingConfig(String heifDecoderCommand) {
}
