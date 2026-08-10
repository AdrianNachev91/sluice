package photos.sluice.application.port.out;

/**
 * Which tier answers for a credential, carrying no credential.
 *
 * <p>A settings screen renders a different sentence per variant, and never shows the value itself.
 * Keeping the value out of this type entirely means a screen cannot leak one it was never handed.
 *
 * <p>Absence is a variant rather than an empty result, so every caller has to say what it renders
 * when nothing is stored.
 */
public sealed interface SecretStatus {

    /**
     * An environment variable answers, so it overrides anything saved. Saving or removing while
     * this is the status changes nothing a read can see. That is the one case a screen has to
     * explain rather than just report.
     *
     * @param variableName {@link String} name of the environment variable holding the value
     */
    record InEnvironment(String variableName) implements SecretStatus {
    }

    /**
     * The machine's own credential store answers.
     */
    record InKeyring() implements SecretStatus {
    }

    /**
     * A protected file in the app-data directory answers, the fallback where no credential store
     * does. It is protected by file permissions rather than encryption, so the sentence a screen
     * shows for it cannot be the one it shows for a keyring.
     */
    record InFile() implements SecretStatus {
    }

    /**
     * No tier holds a value.
     */
    record Absent() implements SecretStatus {
    }
}
