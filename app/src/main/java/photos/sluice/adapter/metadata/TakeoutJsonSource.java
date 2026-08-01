package photos.sluice.adapter.metadata;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.domain.dating.DateSource;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * A {@link DateSource} that reads a capture date from a Google Takeout sidecar's
 * {@code photoTakenTime} field. The sidecar JSON shape is
 * {@code {"photoTakenTime": {"timestamp": "<unix seconds>", ...}, ...}}.
 */
@Component
public class TakeoutJsonSource implements DateSource {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * Resolves a capture date from the Takeout sidecar's photoTakenTime field.
     *
     * @param file {@link MediaFile} unused for this source
     * @param sidecar {@link TakeoutSidecar} the Takeout sidecar JSON to read the timestamp from
     * @return an {@link Optional} {@link LocalDateTime}, the parsed timestamp, if the sidecar is present and
     * well-formed
     */
    @Override
    public Optional<LocalDateTime> resolve(final MediaFile file, final @Nullable TakeoutSidecar sidecar) {
        if (sidecar == null) {
            return Optional.empty();
        }
        try (final var input = Files.newInputStream(sidecar.jsonPath())) {
            final JsonNode root = MAPPER.readTree(input);
            final JsonNode timestamp = root.path("photoTakenTime").path("timestamp");
            // A missing node's .asLong() silently defaults to 0 (the Unix epoch) - checked
            // explicitly so a malformed sidecar can't masquerade as a trusted 1970 date.
            if (!timestamp.isString()) {
                return Optional.empty();
            }
            final long epochSeconds = Long.parseLong(timestamp.asString());
            return Optional.of(
                    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (IOException | JacksonException | NumberFormatException | DateTimeException _) {
            // DateTimeException: a corrupted or hand-edited timestamp field can hold a value
            // Instant's own range rejects outright (Long.parseLong succeeds; ofEpochSecond then
            // throws). Degrading here, like every other malformed-field case, keeps one bad sidecar
            // from aborting the whole sort run. The date source chain falls through to the next one.
            return Optional.empty();
        }
    }
}
