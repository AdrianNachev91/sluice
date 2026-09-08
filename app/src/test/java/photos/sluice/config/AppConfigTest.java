package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.secrets.SecretId;
import photos.sluice.secrets.SecretStatus;
import photos.sluice.secrets.SecretStore;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    // "no-keyring" matches no platform the store writes a keyring for, which leaves the directory
    // it was handed as the only place a save can go. A real os.name here would reach this machine's
    // own credential store and write to it.
    private static final String NO_KEYRING = "no-keyring";
    private static final SecretId A_CREDENTIAL = new SecretId("a-provider", "A_PROVIDER_KEY");

    @Test
    void aCredentialIsSavedIntoTheDirectoryTheBeanWasGiven(@TempDir final Path secretsDir) {
        final SecretStore store = AppConfig.credentialStore(NO_KEYRING, secretsDir);

        store.save(A_CREDENTIAL, "a-key");

        assertThat(secretsDir.resolve("a-provider.key")).exists();
    }

    @Test
    void aCredentialSavedThereIsReadBackFromThere(@TempDir final Path secretsDir) {
        final SecretStore store = AppConfig.credentialStore(NO_KEYRING, secretsDir);
        store.save(A_CREDENTIAL, "a-key");

        assertThat(store.secret(A_CREDENTIAL)).contains("a-key");
        assertThat(store.status(A_CREDENTIAL)).isInstanceOf(SecretStatus.InFile.class);
    }
}
