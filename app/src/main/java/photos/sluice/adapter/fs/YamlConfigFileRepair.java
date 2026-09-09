package photos.sluice.adapter.fs;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.ConfigFileRepairPort;
import photos.sluice.application.port.out.MalformedSettingsException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Repairs the user's YAML config file, through the same document mechanics that write it.
 *
 * <p>A setting is taken out by key rather than by line. A YAML value can span several lines as a
 * block scalar or a flow sequence, so cutting at a line could leave a file the parser then refuses.
 */
public class YamlConfigFileRepair implements ConfigFileRepairPort {

    private static final String SET_ASIDE_MARKER = ".broken-";

    // The same yyyy-MM-dd_HH-mm-ss UTC shape every other kept-aside artifact in this app embeds in
    // its own name. Colon-free, because a Windows path segment cannot hold one. Matched rather than
    // shared, since the class holding it is not visible from here. What one shape buys is a person
    // recognising these names across the app; nothing parses them back.
    private static final DateTimeFormatter SET_ASIDE_STAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);

    // How many names one moment may need before this gives up. Needing a second one at all takes
    // two repairs inside one second. The bound exists so the search terminates, and its refusal
    // says where to look, since nothing removes a kept file and deleting some by hand is the way
    // out.
    private static final int SET_ASIDE_ATTEMPT_LIMIT = 100;

    private final YamlConfigFile document;
    private final Clock clock;

    /**
     * Creates the repair over the given config file.
     *
     * @param configFile {@link Path} the user's YAML config file
     */
    public YamlConfigFileRepair(final Path configFile) {
        this(new YamlConfigFile(configFile), Clock.systemUTC());
    }

    /**
     * Creates the repair over the given document and clock. Package-private so a test can supply a
     * document whose write fails, and a clock that stands still. A moving clock hands every
     * set-aside file a name of its own, which is the one case the collision search exists for.
     *
     * @param document {@link YamlConfigFile} the config file to read and write through
     * @param clock {@link Clock} what the moment in a set-aside name is read from
     */
    YamlConfigFileRepair(final YamlConfigFile document, final Clock clock) {
        this.document = document;
        this.clock = clock;
    }

    /**
     * Takes one setting out of the config file and writes the rest of it back.
     *
     * <p>The file is only rewritten when the setting was actually in it. Writing a file this
     * changes nothing in would put the user's own settings through a serializer for no reason.
     *
     * <p>A setting inside a list, which config binding names with an index, is not removable this
     * way and reports that it changed nothing.
     *
     * <p>A group the file spells another way is written back in the app's own spelling, the same
     * as an ordinary save does.
     *
     * @param property {@link String} the setting, dotted as config binding names it
     * @return boolean true when the file was rewritten, false when it held no such setting
     * @throws MalformedSettingsException if the file cannot be parsed, or holds something other
     *     than a group of settings where the setting would sit
     * @throws UncheckedIOException if the file cannot be read or written
     */
    @Override
    public boolean removeSetting(final String property) {
        final String[] segments = property.split("\\.");
        final Map<String, Object> root = this.document.read();
        Map<String, Object> group = root;
        for (int depth = 0; depth < segments.length - 1; depth++) {
            group = this.document.ensureGroup(group, segments[depth]);
        }
        if (YamlConfigFile.remove(group, segments[segments.length - 1]) == null) {
            return false;
        }
        this.document.write(root);
        return true;
    }

    /**
     * Moves the config file aside, under the name it had plus a marker and the moment. The file
     * stays legible whatever is wrong with it. That is what somebody retyping their own categories
     * out of it needs, and nothing here reads it.
     *
     * <p>An earlier set-aside file may be the only copy of settings the user still wants, so a
     * repair never takes a name that is already there. Nothing here or anywhere else removes one
     * again. So a refusal names the folder and says which files to delete, since doing that by hand
     * is the only way out.
     *
     * @return {@link Path} where the file was moved to
     * @throws UncheckedIOException if the file cannot be moved, or no name is left for this moment
     */
    @Override
    public Path setAside() {
        final Path source = this.document.path();
        final String moment = SET_ASIDE_STAMP_FORMATTER.format(this.clock.instant());
        for (int attempt = 1; attempt <= SET_ASIDE_ATTEMPT_LIMIT; attempt++) {
            final Path moved = this.moveAside(source, source.resolveSibling(setAsideName(source, moment, attempt)));
            if (moved != null) {
                return moved;
            }
        }
        throw new UncheckedIOException("Sluice could not set your settings file aside, because it "
                + "could not find a free name in " + source.toAbsolutePath().getParent()
                + ". Delete some of the files there whose names carry " + SET_ASIDE_MARKER
                + ", then try again.",
                new IOException("every set-aside name for " + moment + " is taken"));
    }

    /**
     * Moves the file to one candidate name, answering null when that name is taken.
     *
     * <p>Refusing to replace is what makes this safe under a collision, rather than a check
     * beforehand that another process could invalidate in between.
     *
     * @param source {@link Path} the config file to move
     * @param candidate {@link Path} the name to try
     * @return {@link Path} where the file went, or null when the name was taken
     * @throws UncheckedIOException if the move failed for any other reason
     */
    private @Nullable Path moveAside(final Path source, final Path candidate) {
        try {
            return Files.move(source, candidate);
        } catch (final FileAlreadyExistsException taken) {
            return null;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to move the settings file " + source
                    + " to " + candidate, e);
        }
    }

    /**
     * The name a set-aside file takes on the given attempt. The original name and extension stay,
     * with the marker and the moment between them. A folder of these then reads as a dated list of
     * what was set aside. A later attempt numbers the same moment.
     *
     * @param source {@link Path} the config file being moved
     * @param moment {@link String} when it was set aside, formatted
     * @param attempt int which attempt this is, counted from one
     * @return {@link String} the file name to try
     */
    private static String setAsideName(final Path source, final String moment, final int attempt) {
        final String leaf = source.getFileName().toString();
        final int dot = leaf.lastIndexOf('.');
        final String base = dot <= 0 ? leaf : leaf.substring(0, dot);
        final String extension = dot <= 0 ? "" : leaf.substring(dot);
        final String marker = attempt == 1 ? SET_ASIDE_MARKER + moment : SET_ASIDE_MARKER + moment + "-" + attempt;
        return base + marker + extension;
    }
}
