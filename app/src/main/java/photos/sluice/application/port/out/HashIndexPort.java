package photos.sluice.application.port.out;

import photos.sluice.domain.model.IndexEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface HashIndexPort {

    Map<String, List<Path>> load();

    boolean contains(String sha256);

    void append(List<IndexEntry> entries);

    // For a caller appending many entries over a long-running move loop (Commit/RescueEngine). One
    // session amortizes the header/leading-newline checks across the whole run instead of redoing
    // them on every entry. Each entry is still flushed as it's written, so a crash mid-run never
    // loses an already-moved file's row.
    Session openSession();

    interface Session extends AutoCloseable {

        void append(IndexEntry entry);

        @Override
        void close();
    }
}
