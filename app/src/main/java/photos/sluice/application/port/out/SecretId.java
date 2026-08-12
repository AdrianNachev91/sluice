package photos.sluice.application.port.out;

import photos.sluice.domain.paths.ReservedDeviceNames;

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
     * <p>A provider naming a Windows device is refused too, for the reason and on the list
     * {@link ReservedDeviceNames} states.
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
        if (ReservedDeviceNames.isReserved(provider)) {
            throw new IllegalArgumentException("Secret id provider '" + provider
                    + "' is a reserved device name on Windows and cannot become a file there");
        }
        if (environmentVariable.isBlank()) {
            throw new IllegalArgumentException(
                    "Secret id for provider '" + provider + "' needs an environment variable name");
        }
    }
}
