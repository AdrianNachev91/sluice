package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MissingCredentialExceptionTest {

    private static final SecretId ID = new SecretId("anthropic", "ANTHROPIC_API_KEY");

    @Test
    void theCredentialIsCarriedThroughAsAValue() {
        assertThat(new MissingCredentialException(ID, "no key").id()).isEqualTo(ID);
    }

    @Test
    void aMissingCredentialIsAnIllegalStateException() {
        assertThat(new MissingCredentialException(ID, "no key")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingCredentialIsNotAStoreFailure() {
        assertThat(new MissingCredentialException(ID, "no key")).isNotInstanceOf(SecretStoreException.class);
    }
}
