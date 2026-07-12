package photos.sluice.application.port.out;

import photos.sluice.domain.model.IndexEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface HashIndexPort {

    Map<String, List<Path>> load();

    boolean contains(String sha256);

    void append(List<IndexEntry> entries);
}
