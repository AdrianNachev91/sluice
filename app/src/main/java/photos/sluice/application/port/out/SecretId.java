package photos.sluice.application.port.out;

import java.util.regex.Pattern;

/**
 * Names one credential, and the environment variable that overrides it.
 *
 * <p>A provider adapter builds its own. Nothing central maps providers to secrets, so adding a
 * second API provider means that provider naming its own key rather than editing a registry
 * somewhere else.
 *
 * <p>The environment variable name travels with the id because a status line quotes it back to the
 * user, and it differs per provider.
 *
 * @param provider {@link String} id of the vision provider this credential belongs to
 * @param environmentVariable {@link String} name of the environment variable that overrides it
 */
public record SecretId(String provider, String environmentVariable) {

    private static final Pattern PROVIDER = Pattern.compile("[a-z0-9-]+");
    private static final Pattern RESERVED_DEVICE = Pattern.compile("con|prn|aux|nul|com[1-9]|lpt[1-9]");

    /**
     * Refuses an id missing either half, or naming a provider that cannot safely become a filename.
     * Both halves locate a stored value, so neither can be absent without the lookup silently
     * reading somewhere else.
     *
     * <p>The provider becomes part of a path under the credential directory. A plugin supplying its
     * own id is contemplated by this project's licence, so the shape a third party may choose is
     * pinned here rather than assumed. Anything outside lower-case letters, digits and hyphens is
     * refused, which leaves no separator or parent reference to escape that directory with.
     *
     * <p>Windows device names are refused on every platform, not only on Windows. Windows resolves
     * one whatever extension follows it, so a provider called {@code con} would name the console
     * rather than a file. Refusing it everywhere means a plugin author cannot ship an id that works
     * on the machine they built it on and breaks on someone else's.
     *
     * <p>The device list stops at {@code com1} and {@code lpt1}. Microsoft's own naming guidance
     * includes {@code com0} and {@code lpt0}, and a probe on Windows 10 disagreed with it. Both
     * create as ordinary files there, while {@code com1} and {@code lpt1} are refused. Refusing a
     * name that works would be its own defect.
     *
     * @param provider {@link String} id of the vision provider this credential belongs to
     * @param environmentVariable {@link String} name of the environment variable that overrides it
     */
    public SecretId {
        if (provider.isBlank()) {
            throw new IllegalArgumentException("Secret id needs a provider");
        }
        if (!PROVIDER.matcher(provider).matches()) {
            throw new IllegalArgumentException("Secret id provider '" + provider
                    + "' may hold only lower-case letters, digits and hyphens");
        }
        if (RESERVED_DEVICE.matcher(provider).matches()) {
            throw new IllegalArgumentException("Secret id provider '" + provider
                    + "' is a reserved device name on Windows and cannot become a file there");
        }
        if (environmentVariable.isBlank()) {
            throw new IllegalArgumentException(
                    "Secret id for provider '" + provider + "' needs an environment variable name");
        }
    }
}
