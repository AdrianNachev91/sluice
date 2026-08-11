package photos.sluice.adapter.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

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

    private static final Logger log = LoggerFactory.getLogger(PlatformKeyring.class);

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
        if (isLinux(osName)) {
            return linuxSecretService().map(LinuxSecretServiceTier::new);
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
     * Whether the given operating system name is a Linux one.
     *
     * <p>The JVM reports plain {@code Linux} there, with no release riding along. The substring
     * check matches how the other platforms are read rather than being needed for variety.
     *
     * @param osName {@link String} the raw OS name (e.g. system property os.name)
     * @return boolean true when the name is a Linux one
     */
    static boolean isLinux(final String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    /**
     * Binds the Windows Credential Manager, or answers with nothing where this machine has none.
     *
     * @return an {@link Optional} of {@link WindowsCredentialManager}, empty where this machine has
     *         no Windows Credential Manager to bind
     */
    private static Optional<WindowsCredentialManager> windowsCredentialManager() {
        return bindOrExplain(Advapi32CredentialManager::open);
    }

    /**
     * Binds the Secret Service through libsecret, or answers with nothing where this machine has
     * none. A Linux install without a desktop keyring is ordinary, a server most of all. Such a
     * machine keeps its credential in the protected file rather than being a broken one.
     *
     * @return an {@link Optional} of {@link LinuxSecretService}, empty where this machine has no
     *         Secret Service to bind
     */
    private static Optional<LinuxSecretService> linuxSecretService() {
        return bindOrExplain(LibsecretService::open);
    }

    /**
     * Binds a platform's credential store, or answers with nothing and says why.
     *
     * <p>The reason is recorded rather than swallowed, because the ways a binding fails are worth
     * telling apart and only the failure itself knows which happened. A machine without the
     * library reports that it could not be loaded. An installation too old to carry a function
     * this app needs reports which symbol it lacks. That is the difference between "no keyring
     * here" and "your keyring is older than this app supports". Both leave the machine on the
     * protected file, which works, so this is a diagnosis rather than a fault.
     *
     * @param binding a {@link Supplier} that binds one platform's store, or throws
     * @param <T> the bound store's own type
     * @return an {@link Optional} of the bound store, empty where this machine has none
     */
    private static <T> Optional<T> bindOrExplain(final Supplier<T> binding) {
        try {
            return Optional.of(binding.get());
        } catch (final IllegalArgumentException | UnsatisfiedLinkError absent) {
            log.info("Could not bind a credential store on this machine, so credentials go to the"
                    + " protected file instead. Reason: {}", absent.getMessage());
            return Optional.empty();
        }
    }
}
