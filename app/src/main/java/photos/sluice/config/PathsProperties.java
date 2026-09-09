package photos.sluice.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code sluice.paths} settings: the working root, library root, and inbox directories.
 *
 * <p>All three are nullable because this app ships no default paths of its own. An unconfigured
 * install still boots; {@code PathValidationUseCase} is what refuses a job or a save that needs a
 * root none of these carry.
 */
@ConfigurationProperties(prefix = "sluice.paths")
public record PathsProperties(@Nullable String workingRoot, @Nullable String libraryRoot, @Nullable String inbox) {
}
