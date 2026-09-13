package photos.sluice.adapter.fs;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.RunEnding;
import photos.sluice.application.port.out.SpendLedgerEntry;
import photos.sluice.application.port.out.SpendLedgerPort;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SpendLedgerPort} backed by a twelve-column CSV file, one row per sift run however it
 * ended.
 *
 * <p>The row format matches the library hash index that sits beside it, so the two files can be
 * read the same way. Fields are always quoted, the encoding is UTF-8, and a leading byte-order mark
 * is tolerated.
 *
 * <p>The file sits under the logs directory of whichever working root is configured, and is
 * resolved on every call rather than once. A user who points Sluice at a different working root is
 * asking about a different install's history.
 *
 * <p>A row nobody can parse is refused rather than skipped. These rows are the only record of what
 * past runs consumed, and nothing can recompute them. So a line that looks wrong is worth stopping
 * on, and {@link #setAside} is the whole of the repair this class offers.
 */
@Component
public class CsvSpendLedger implements SpendLedgerPort {

    private static final String HEADER = "\"ended_at\",\"scope\",\"provider\",\"model\",\"tile_size\","
            + "\"tiles_per_row\",\"montages_sifted\",\"montages_skipped\",\"api_calls\",\"input_tokens\","
            + "\"output_tokens\",\"ending\"";
    private static final int COLUMNS = 12;
    private static final String LEDGER_FILE_NAME = "spend-ledger.csv";
    // A UTF-8 byte-order-mark may appear at the start of the file; Java's UTF-8 decoder does not
    // strip it automatically, so it survives as a literal leading character.
    private static final char BOM = '﻿';

    private final PathsPort paths;

    /**
     * Creates a ledger over the logs directory of the configured working root.
     *
     * @param paths {@link PathsPort} resolves the logs directory
     */
    public CsvSpendLedger(final PathsPort paths) {
        this.paths = paths;
    }

    /**
     * Reads every recorded run, in the order they were appended.
     *
     * @return a {@link List} of {@link SpendLedgerEntry} the recorded runs, empty when the ledger
     *         file does not exist
     */
    @Override
    public List<SpendLedgerEntry> read() {
        final Path ledgerFile = this.ledgerFile();
        if (!Files.isRegularFile(ledgerFile)) {
            return List.of();
        }
        final List<SpendLedgerEntry> entries = new ArrayList<>();
        try {
            final List<String> lines = Files.readAllLines(ledgerFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                // The BOM (if present) and the header row only ever appear on line 0.
                final String line = i == 0 ? stripBom(lines.get(i)) : lines.get(i);
                final boolean isHeaderRow = i == 0 && line.equals(HEADER);
                if (!line.isBlank() && !isHeaderRow) {
                    entries.add(parseLine(line));
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read spend ledger " + ledgerFile, e);
        }
        return List.copyOf(entries);
    }

    /**
     * Appends one run to the ledger, creating the file and its header if this is the first.
     *
     * @param entry {@link SpendLedgerEntry} the run to record
     */
    @Override
    public void append(final SpendLedgerEntry entry) {
        final Path ledgerFile = this.ledgerFile();
        try (final BufferedWriter writer = openWriter(ledgerFile)) {
            writer.write(formatLine(entry));
            writer.newLine();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to append to spend ledger " + ledgerFile, e);
        }
    }

    /**
     * Moves the ledger file to destination, so the next read finds nothing.
     *
     * @param destination {@link Path} where the ledger file is moved to
     * @return boolean true when there was a ledger file to move
     */
    @Override
    public boolean setAside(final Path destination) {
        final Path ledgerFile = this.ledgerFile();
        if (!Files.isRegularFile(ledgerFile)) {
            return false;
        }
        try {
            Files.createDirectories(destination.getParent());
            Files.move(ledgerFile, destination);
            return true;
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not file the spend ledger aside at " + destination, e);
        }
    }

    /**
     * The ledger file under the configured logs directory.
     *
     * @return {@link Path} the CSV spend ledger file
     */
    private Path ledgerFile() {
        return this.paths.logs().resolve(LEDGER_FILE_NAME);
    }

    /**
     * Opens the ledger file for appending, writing a leading newline and/or header as needed.
     *
     * @param ledgerFile {@link Path} the CSV spend ledger file to append to
     * @return a {@link BufferedWriter} positioned to append a new row
     */
    private static BufferedWriter openWriter(final Path ledgerFile) throws IOException {
        Files.createDirectories(ledgerFile.getParent());
        final boolean exists = Files.isRegularFile(ledgerFile);
        final long size = exists ? Files.size(ledgerFile) : 0;
        final boolean writeHeader = !exists || size == 0;
        // Defensive: the file can arrive here without a trailing newline (a manual edit, an
        // editor that strips trailing whitespace, an interrupted write). Appending straight
        // onto such a line would merge it with the next row into one unparsable line and
        // break read() for the whole file.
        final boolean needsLeadingNewline = size > 0 && !endsWithNewline(ledgerFile);
        final BufferedWriter writer = Files.newBufferedWriter(ledgerFile, StandardCharsets.UTF_8,
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
     * Reads only the file's last byte via a seek, rather than loading the whole file. This check
     * runs on every append, and the ledger grows for the life of the install.
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
     * <p>A provider that calls no model writes an empty model field, which reads back as null. No
     * model id is ever the empty string, so nothing real is lost to that round trip.
     *
     * @param entry {@link SpendLedgerEntry} to format
     * @return {@link String} the formatted CSV row
     */
    private static String formatLine(final SpendLedgerEntry entry) {
        return String.join(",",
                quote(entry.endedAt().toString()),
                quote(entry.scope()),
                quote(entry.providerId()),
                quote(entry.modelId() == null ? "" : entry.modelId()),
                quote(String.valueOf(entry.tileSize())),
                quote(String.valueOf(entry.tilesPerRow())),
                quote(String.valueOf(entry.montagesSifted())),
                quote(String.valueOf(entry.montagesSkipped())),
                quote(String.valueOf(entry.apiCalls())),
                quote(String.valueOf(entry.inputTokens())),
                quote(String.valueOf(entry.outputTokens())),
                quote(entry.ending().name()));
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
     * Parses one CSV row into a ledger entry.
     *
     * @param line {@link String} CSV row to parse
     * @return {@link SpendLedgerEntry} the parsed entry
     */
    private static SpendLedgerEntry parseLine(final String line) {
        final List<String> fields = parseCsvFields(line);
        if (fields.size() != COLUMNS) {
            throw new IllegalStateException("Malformed spend ledger line: " + line);
        }
        try {
            final String modelId = fields.get(3);
            return new SpendLedgerEntry(
                    Instant.parse(fields.get(0)),
                    fields.get(1),
                    fields.get(2),
                    modelId.isEmpty() ? null : modelId,
                    Integer.parseInt(fields.get(4)),
                    Integer.parseInt(fields.get(5)),
                    Integer.parseInt(fields.get(6)),
                    Integer.parseInt(fields.get(7)),
                    Integer.parseInt(fields.get(8)),
                    Long.parseLong(fields.get(9)),
                    Long.parseLong(fields.get(10)),
                    RunEnding.valueOf(fields.get(11)));
        } catch (final DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalStateException("Malformed spend ledger line: " + line, e);
        }
    }

    /**
     * Hand-rolled instead of pulling in a CSV library: every field is always quoted, so this is a
     * small state machine rather than a general-purpose parser. A {@code "} inside a field is
     * either an escaped literal quote or the field's closing one, and peeking at the next character
     * is what tells them apart.
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
