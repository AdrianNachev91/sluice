package photos.sluice.adapter.fs;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.config.PathsConfig;
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

// Format matches an existing on-disk hash index this adapter must stay interoperable with:
// 2 columns, always-quoted, UTF-8 encoded with a leading byte-order mark.
@Component
public class CsvLibraryHashIndex implements HashIndexPort {

    private static final String HEADER = "\"sha256\",\"path\"";
    // A UTF-8 byte-order-mark may appear at the start of the file; Java's UTF-8 decoder does not
    // strip it automatically, so it survives as a literal leading character.
    private static final char BOM = '﻿';

    private final PathsConfig pathsConfig;

    public CsvLibraryHashIndex(PathsConfig pathsConfig) {
        this.pathsConfig = pathsConfig;
    }

    @Override
    public Map<String, List<Path>> load() {
        Path file = indexFile();
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, List<Path>> result = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                // The BOM (if present) and the header row only ever appear on line 0.
                String line = i == 0 ? stripBom(lines.get(i)) : lines.get(i);
                boolean isHeaderRow = i == 0 && line.equals(HEADER);
                if (!line.isBlank() && !isHeaderRow) {
                    IndexEntry entry = parseLine(line);
                    // The same file/hash can legitimately appear more than once (a byte-identical
                    // copy filed under two names/locations), so group by hash instead of
                    // overwriting.
                    result.computeIfAbsent(entry.sha256(), key -> new ArrayList<>()).add(entry.path());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read hash index " + file, e);
        }
        return result;
    }

    @Override
    public boolean contains(String sha256) {
        // No caching: every caller so far either already holds a pre-loaded Set or calls this
        // rarely enough that re-reading the file each time is not worth the staleness risk of a
        // cache that could drift if the file is modified outside this process.
        return load().containsKey(sha256);
    }

    @Override
    public void append(List<IndexEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Path file = indexFile();
        try {
            Files.createDirectories(file.getParent());
            boolean exists = Files.isRegularFile(file);
            long size = exists ? Files.size(file) : 0;
            boolean writeHeader = !exists || size == 0;
            // Defensive: the file can arrive here without a trailing newline (a manual edit, an
            // editor that strips trailing whitespace, an interrupted write). Appending straight
            // onto such a line would merge it with the next row into one unparsable line and
            // break load() for the whole file.
            boolean needsLeadingNewline = size > 0 && !endsWithNewline(file);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (needsLeadingNewline) {
                    writer.newLine();
                }
                if (writeHeader) {
                    writer.write(HEADER);
                    writer.newLine();
                }
                for (IndexEntry entry : entries) {
                    writer.write(formatLine(entry));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to hash index " + file, e);
        }
    }

    // Reads only the file's last byte via a seek, rather than loading the whole file, since this
    // check runs on every append and the index can grow to many thousands of rows.
    private static boolean endsWithNewline(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(channel.size() - 1);
            ByteBuffer buffer = ByteBuffer.allocate(1);
            channel.read(buffer);
            byte last = buffer.get(0);
            return last == '\n' || last == '\r';
        }
    }

    private Path indexFile() {
        return pathsConfig.logs().resolve("library-hashes.csv");
    }

    private static String formatLine(IndexEntry entry) {
        return quote(entry.sha256()) + "," + quote(entry.path().toString());
    }

    // RFC 4180: a literal quote inside a quoted field is escaped by doubling it.
    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String stripBom(String line) {
        return !line.isEmpty() && line.charAt(0) == BOM ? line.substring(1) : line;
    }

    private static IndexEntry parseLine(String line) {
        List<String> fields = parseCsvFields(line);
        if (fields.size() != 2) {
            throw new IllegalStateException("Malformed hash index line: " + line);
        }
        return new IndexEntry(fields.get(0), Path.of(fields.get(1)));
    }

    // Hand-rolled instead of pulling in a CSV library: the format is fixed at exactly 2 always-
    // quoted columns, so this is a small character-by-character state machine rather than a
    // general-purpose parser. `inQuotes` tracks whether we're inside a quoted field; a `"` seen
    // while inside one is either an escaped literal quote (doubled - consume both, stay in the
    // field) or the field's closing quote, disambiguated by peeking at the next character.
    private static List<String> parseCsvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
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
