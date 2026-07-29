package photos.sluice.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code sluice.paths} settings: the repo root, library root, and inbox directories.
 *
 * <p>All three are nullable because this app ships no default paths of its own. {@link PathsConfig}
 * fails startup with an actionable message when one is missing.
 */
@ConfigurationProperties(prefix = "sluice.paths")
public record PathsProperties(@Nullable String repoRoot, @Nullable String libraryRoot, @Nullable String inbox) {
}
