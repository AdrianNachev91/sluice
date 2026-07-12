package photos.sluice.adapter.metadata;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.DateSource;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FilenameSource implements DateSource {

    // Accepts run-together (YYYYMMDD) and separated (YYYY-MM-DD / YYYY_MM_DD / YYYY.MM.DD) forms,
    // matching filenames like IMG_20210315_103000.jpg or IMG-20210315-WA0001.jpg. Folder routing
    // only needs year/month/day, so any embedded time component is deliberately not captured.
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(20\\d{2}|19\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})");

    @Override
    public Optional<LocalDateTime> resolve(MediaFile file, TakeoutSidecar sidecar) {
        Matcher matcher = DATE_PATTERN.matcher(file.path().getFileName().toString());
        if (!matcher.find()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        try {
            return Optional.of(LocalDate.of(year, month, day).atStartOfDay());
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
