package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sluice.paths")
public record PathsProperties(String repoRoot, String libraryRoot, String inbox) {
}
