package photos.sluice.application.port.out;

import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Puts the user's config file back into a state the app can start from. Separate from
 * {@link SettingsStore} because it runs when no settings could be loaded at all. There is nothing
 * to save at that point, and no context to save it through.
 *
 * <p>The two repairs answer the two ways a config file stops an app starting. A file that parses
 * has a document, so one setting can be taken out of it. A file that does not parse has no
 * document, so the whole file goes aside.
 */
public interface ConfigFileRepairPort {

    /**
     * Takes one setting out of the config file and leaves everything else in it.
     *
     * @param property {@link String} the setting, dotted as config binding names it
     * @return boolean true when the file was rewritten, false when it held no such setting
     * @throws MalformedSettingsException if the file cannot be parsed, or holds something other
     *     than a group of settings where the setting would sit
     * @throws UncheckedIOException if the file cannot be read or written
     */
    boolean removeSetting(String property);

    /**
     * Renames the config file aside, so the app next starts with nothing configured. The file is
     * kept rather than deleted, because it is the only copy of whatever the user wrote in it.
     *
     * <p>Nothing removes a kept file afterwards, which is the user's to do. So this can refuse once
     * an implementation has no unused name left, and the refusal says so in words they can act on.
     *
     * @return {@link Path} where the file was moved to
     * @throws UncheckedIOException if the file cannot be moved, or no name is left for it
     */
    Path setAside();
}
