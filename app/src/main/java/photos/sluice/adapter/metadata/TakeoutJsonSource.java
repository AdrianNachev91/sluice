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
     * @return an {@link Optional} {@link LocalDateTime}, the parsed timestamp, if the sidecar is present and well-formed
     */
    @Override
    public Optional<LocalDateTime> resolve(MediaFile file, @Nullable TakeoutSidecar sidecar) {
        if (sidecar == null) {
            return Optional.empty();
        }
        try (var input = Files.newInputStream(sidecar.jsonPath())) {
            JsonNode root = MAPPER.readTree(input);
            JsonNode timestamp = root.path("photoTakenTime").path("timestamp");
            // A missing node's .asLong() silently defaults to 0 (the Unix epoch) - checked
            // explicitly so a malformed sidecar can't masquerade as a trusted 1970 date.
            if (!timestamp.isString()) {
                return Optional.empty();
            }
            long epochSeconds = Long.parseLong(timestamp.asString());
            return Optional.of(
                    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (IOException | JacksonException | NumberFormatException _) {
            return Optional.empty();
        }
    }
}
