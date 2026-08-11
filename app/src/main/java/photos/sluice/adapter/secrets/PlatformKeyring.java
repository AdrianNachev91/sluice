package photos.sluice.adapter.secrets;

import java.util.Locale;
import java.util.Optional;

/**
 * Picks the credential store this machine's operating system offers, where it offers one.
 *
 * <p>The one place a platform's credential store is registered. A machine whose platform has no
 * binding here, or has one that will not load, gets no keyring tier at all. The store above then
 * falls through to the protected file. That is a working install rather than a degraded one.
 *
 * <p>Answering with nothing is what lets a platform's binding land on its own. Until it does, that
 * platform behaves exactly as it did before any binding existed.
 *
 * <p>The operating system's name arrives from the caller, and the reading of it happens here. An
 * adapter cannot reach the code that locates the config directory, so sharing a predicate with it
 * was never available. It would also be the wrong thing to share. Where a config file belongs is a
 * filesystem convention. Which credential store to bind is a question about the libraries a platform
 * ships. The two could diverge without either being wrong.
 */
final class PlatformKeyring {

    /**
     * Prevents instantiation of this static utility class.
     */
    private PlatformKeyring() {
    }

    /**
     * Resolves the credential store tier for the given operating system.
     *
     * @param osName {@link String} the raw OS name (e.g. system property os.name)
     * @return an {@link Optional} of {@link WritableSecretTier}, empty where this platform offers no
     *         credential store Sluice can reach
     */
    static Optional<WritableSecretTier> forThisMachine(final String osName) {
        if (isWindows(osName)) {
            return windowsCredentialManager().map(WindowsCredentialTier::new);
        }
        return Optional.empty();
    }

    /**
     * Whether the given operating system name is a Windows one.
     *
     * <p>Matched on a substring rather than on a list of releases. The name carries its release
     * with it, and every release is a fresh string. Windows 10, Windows Server 2022 and anything
     * later all read the same way here.
     *
     * @param osName {@link String} the raw OS name (e.g. system property os.name)
     * @return boolean true when the name is a Windows one
     */
    static boolean isWindows(final String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Binds the Windows Credential Manager, or answers with nothing where this machine has none.
     *
     * <p>Turning a refusal into an absent tier is this class's decision rather than the binding's.
     * The binding reports what the machine did, and what that means is the question this class
     * exists to answer.
     *
     * <p>The catch is wider than the two causes worth naming, and cannot be narrowed by type. A
     * function descriptor written wrong here throws the same exception an absent library does. The
     * round-trip test is what stops that shipping as a silent downgrade to the file tier. It binds
     * the credential store directly rather than through this method, so a wrong descriptor fails the
     * build instead of quietly reducing that test to nothing.
     *
     * @return an {@link Optional} of {@link WindowsCredentialManager}, empty where this machine has
     *         no Windows Credential Manager to bind
     */
    private static Optional<WindowsCredentialManager> windowsCredentialManager() {
        try {
            return Optional.of(Advapi32CredentialManager.open());
        } catch (final IllegalArgumentException | UnsatisfiedLinkError absent) {
            return Optional.empty();
        }
    }
}
