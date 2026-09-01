package photos.sluice.application.port.out;

import photos.sluice.domain.model.IndexEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The effect boundary application services use to read and update the persisted hash index that
 * backs duplicate detection. Every entry maps a file's SHA-256 hash to every known library path
 * holding those bytes.
 */
public interface HashIndexPort {

    /**
     * Loads the full hash index.
     *
     * @return a {@link Map} of {@link String} to a {@link List} of {@link Path}, every indexed hash mapped to its
     * known paths
     */
    Map<String, List<Path>> load();

    /**
     * Checks whether a hash is already present in the index.
     *
     * @param sha256 {@link String} the hash to look up
     * @return boolean true if the hash is already indexed
     */
    boolean contains(String sha256);

    /**
     * Appends entries to the index.
     *
     * @param entries a {@link List} of {@link IndexEntry} the entries to append
     */
    void append(List<IndexEntry> entries);

    /**
     * Moves the index to destination, leaving nothing in its place, so the next read starts from
     * an empty one.
     *
     * <p>Moved rather than deleted. The index is the record of what the library already holds.
     * Throwing it away is the one thing pointing Sluice back at a folder cannot undo. A caller
     * picks somewhere it will be found again.
     *
     * @param destination {@link Path} where the index is moved to, its parents created as needed
     * @return boolean true when there was an index to move
     */
    boolean setAside(Path destination);

    /**
     * For a caller appending many entries over a long-running move loop. One session amortizes the
     * header/leading-newline checks across the whole run instead of redoing them on every entry.
     * Each entry is still flushed as it's written, so a crash mid-run never loses an already-moved
     * file's row.
     *
     * @return {@link Session} an open session for batched appends
     */
    Session openSession();

    /**
     * A batched-append handle opened by {@link #openSession()} for a caller writing many entries
     * over a long-running move loop. Amortizes one-time setup (header and leading-newline checks)
     * across the whole run. Each entry is still flushed as it is appended, so a crash mid-run never
     * loses an already-moved file's row.
     */
    interface Session extends AutoCloseable {

        /**
         * Appends a single entry within this session.
         *
         * @param entry {@link IndexEntry} the entry to append
         */
        void append(IndexEntry entry);

        /**
         * Closes the session, flushing any pending state.
         */
        @Override
        void close();
    }
}
