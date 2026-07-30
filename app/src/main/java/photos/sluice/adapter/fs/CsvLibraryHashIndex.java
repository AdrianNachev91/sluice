package photos.sluice.adapter.fs;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.domain.model.IndexEntry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link HashIndexPort} backed by a two-column CSV file: one row per hashed file, holding its
 * sha256 hash and its path. The row format is fixed: always-quoted fields, UTF-8 encoding, and an
 * optional leading byte-order mark. That keeps this adapter interoperable with an existing
 * on-disk hash index in that exact shape.
 */
public class CsvLibraryHashIndex implements HashIndexPort {

    private static final String HEADER = "\"sha256\",\"path\"";
    // A UTF-8 byte-order-mark may appear at the start of the file; Java's UTF-8 decoder does not
    // strip it automatically, so it survives as a literal leading character.
    private static final char BOM = '﻿';

    private final Path indexFile;

    /**
     * Creates an index backed by a CSV file at the given path.
     *
     * @param indexFile {@link Path} path to the CSV hash index file
     */
    public CsvLibraryHashIndex(final Path indexFile) {
        this.indexFile = indexFile;
    }

    /**
     * Loads every entry from the index file, grouped by hash.
     *
     * @return a {@link Map} of {@link String} to a {@link List} of {@link Path}, grouped by
     *     their sha256 hash, empty if the index file does not exist
     */
    @Override
    public Map<String, List<Path>> load() {
        if (!Files.isRegularFile(indexFile)) {
            return Map.of();
        }
        final Map<String, List<Path>> result = new LinkedHashMap<>();
        try {
            final List<String> lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                // The BOM (if present) and the header row only ever appear on line 0.
                final String line = i == 0 ? stripBom(lines.get(i)) : lines.get(i);
                final boolean isHeaderRow = i == 0 && line.equals(HEADER);
                if (!line.isBlank() && !isHeaderRow) {
                    final IndexEntry entry = parseLine(line);
                    // The same file/hash can legitimately appear more than once (a byte-identical
                    // copy filed under two names/locations), so group by hash instead of
                    // overwriting.
                    result.computeIfAbsent(entry.sha256(), _ -> new ArrayList<>()).add(entry.path());
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read hash index " + indexFile, e);
        }
        return result;
    }

    /**
     * Checks whether a hash is already present in the index.
     *
     * @param sha256 {@link String} hash to look up
     * @return boolean true if the hash appears in the index
     */
    @Override
    public boolean contains(final String sha256) {
        // No caching: every caller so far either already holds a pre-loaded Set or calls this
        // rarely enough that re-reading the file each time is not worth the staleness risk of a
        // cache that could drift if the file is modified outside this process.
        return load().containsKey(sha256);
    }

    /**
     * Appends a batch of entries to the index file in a single session.
     *
     * @param entries a {@link List} of {@link IndexEntry} to append
     */
    @Override
    public void append(final List<IndexEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        try (final Session session = openSession()) {
            entries.forEach(session::append);
        }
    }

    /**
     * Opens a new append session against this index file.
     *
     * @return a new {@link Session} for appending entries
     */
    @Override
    public Session openSession() {
        return new CsvSession();
    }

    /**
     * A {@link Session} that lazily opens its writer, and performs the header/leading-newline
     * checks that precede opening it, on the first {@link #append} call rather than on
     * construction. A session that never appends anything - an empty commit or rescue scope -
     * leaves the index file untouched.
     */
    private final class CsvSession implements Session {

        private @Nullable BufferedWriter writer;

        /**
         * Appends one entry to the index file, opening the writer on first use.
         *
         * @param entry {@link IndexEntry} to append
         */
        @Override
        public void append(final IndexEntry entry) {
            try {
                if (writer == null) {
                    writer = openWriter();
                }
                writer.write(formatLine(entry));
                writer.newLine();
                writer.flush();
            } catch (final IOException e) {
                throw new UncheckedIOException("Failed to append to hash index " + indexFile, e);
            }
        }

        /**
         * Closes the underlying writer, if one was ever opened.
         */
        @Override
        public void close() {
            if (writer == null) {
                return;
            }
            try {
                writer.close();
            } catch (final IOException e) {
                throw new UncheckedIOException("Failed to close hash index " + indexFile, e);
            }
        }
    }

    /**
     * Opens the index file for appending, writing a leading newline and/or header as needed.
     *
     * @return a {@link BufferedWriter} positioned to append new rows
     */
    private BufferedWriter openWriter() throws IOException {
        Files.createDirectories(indexFile.getParent());
        final boolean exists = Files.isRegularFile(indexFile);
        final long size = exists ? Files.size(indexFile) : 0;
        final boolean writeHeader = !exists || size == 0;
        // Defensive: the file can arrive here without a trailing newline (a manual edit, an
        // editor that strips trailing whitespace, an interrupted write). Appending straight
        // onto such a line would merge it with the next row into one unparsable line and
        // break load() for the whole file.
        final boolean needsLeadingNewline = size > 0 && !endsWithNewline(indexFile);
        final BufferedWriter writer = Files.newBufferedWriter(indexFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (needsLeadingNewline) {
            writer.newLine();
        }
        if (writeHeader) {
            writer.write(HEADER);
            writer.newLine();
        }
        return writer;
    }

    /**
     * Reads only the file's last byte via a seek, rather than loading the whole file, since this
     * check runs on every append and the index can grow to many thousands of rows.
     *
     * @param file {@link Path} file to check
     * @return boolean true if the file's last byte is a newline character
     */
    private static boolean endsWithNewline(final Path file) throws IOException {
        try (final SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(channel.size() - 1);
            final ByteBuffer buffer = ByteBuffer.allocate(1);
            channel.read(buffer);
            final byte last = buffer.get(0);
            return last == '\n' || last == '\r';
        }
    }

    /**
     * Formats an entry as one quoted CSV row.
     *
     * @param entry {@link IndexEntry} to format
     * @return {@link String} the formatted CSV row
     */
    private static String formatLine(final IndexEntry entry) {
        return quote(entry.sha256()) + "," + quote(entry.path().toString());
    }

    /**
     * RFC 4180: a literal quote inside a quoted field is escaped by doubling it.
     *
     * @param value {@link String} field value to quote
     * @return {@link String} the quoted, escaped field value
     */
    private static String quote(final String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * Strips a leading byte-order-mark character, if present.
     *
     * @param line {@link String} line to strip
     * @return {@link String} the line without a leading BOM
     */
    private static String stripBom(final String line) {
        return !line.isEmpty() && line.charAt(0) == BOM ? line.substring(1) : line;
    }

    /**
     * Parses one CSV row into an index entry.
     *
     * @param line {@link String} CSV row to parse
     * @return {@link IndexEntry} the parsed index entry
     */
    private static IndexEntry parseLine(final String line) {
        final List<String> fields = parseCsvFields(line);
        if (fields.size() != 2) {
            throw new IllegalStateException("Malformed hash index line: " + line);
        }
        return new IndexEntry(fields.get(0), Path.of(fields.get(1)));
    }

    /**
     * Hand-rolled instead of pulling in a CSV library: the format is fixed at exactly 2 always-
     * quoted columns, so this is a small character-by-character state machine rather than a
     * general-purpose parser. {@code inQuotes} tracks whether we're inside a quoted field; a
     * {@code "} seen while inside one is either an escaped literal quote (doubled - consume both,
     * stay in the field) or the field's closing quote, disambiguated by peeking at the next
     * character.
     *
     * @param line {@link String} CSV row to split into fields
     * @return a {@link List} of {@link String}, the row's field values, in order
     */
    private static List<String> parseCsvFields(final String line) {
        final List<String> fields = new ArrayList<>();
        final var current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++; // consume both quotes of the doubled pair
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
