package photos.sluice.domain.model;

import java.time.LocalDateTime;

public record DateResult(LocalDateTime when, Confidence confidence, String source) {
}
