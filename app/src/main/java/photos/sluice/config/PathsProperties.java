package photos.sluice.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sluice.paths")
public record PathsProperties(@Nullable String repoRoot, @Nullable String libraryRoot, @Nullable String inbox) {
}
