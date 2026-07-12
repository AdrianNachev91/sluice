package photos.sluice.adapter.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.DateSource;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

// Google Takeout sidecar JSON shape: {"photoTakenTime": {"timestamp": "<unix seconds>", ...}, ...}.
@Component
public class TakeoutJsonSource implements DateSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Optional<LocalDateTime> resolve(MediaFile file, TakeoutSidecar sidecar) {
        if (sidecar == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.newInputStream(sidecar.jsonPath()));
            JsonNode timestamp = root.path("photoTakenTime").path("timestamp");
            // A missing node's .asLong() silently defaults to 0 (the Unix epoch) - checked
            // explicitly so a malformed sidecar can't masquerade as a trusted 1970 date.
            if (!timestamp.isTextual()) {
                return Optional.empty();
            }
            long epochSeconds = Long.parseLong(timestamp.asText());
            return Optional.of(
                    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}
