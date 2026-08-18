package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class VisionProviderDescriptorTest {

    private static final SecretId KEY = new SecretId("a-provider", "A_PROVIDER_KEY");

    @Test
    void aProviderNamingItsOwnCredentialAndUsingOneIsAccepted() {
        assertThatCode(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.MODEL, ProviderSetting.CREDENTIAL), Set.of(ProviderSetting.MODEL), KEY))
                .doesNotThrowAnyException();
    }

    @Test
    void aProviderTakingNoCredentialAndNamingNoneIsAccepted() {
        assertThatCode(() -> new VisionProviderDescriptor("no-key", "No key",
                Set.of(ProviderSetting.WATCH_MODE), Set.of(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void requiringASettingItDoesNotUseIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.WATCH_MODE), Set.of(ProviderSetting.MODEL), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-provider");
    }

    @Test
    void usingACredentialWithoutNamingOneIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.CREDENTIAL), Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namingACredentialWithoutUsingOneIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.MODEL), Set.of(), KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namingAnotherProvidersCredentialIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("second-provider", "Second provider",
                Set.of(ProviderSetting.CREDENTIAL), Set.of(), KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-provider");
    }

    @Test
    void theSetsItAnswersWithAreItsOwn() {
        final var mutable = new HashSet<>(Set.of(ProviderSetting.MODEL));
        final var descriptor = new VisionProviderDescriptor("a-provider", "A provider", mutable, Set.of(), null);

        mutable.add(ProviderSetting.WATCH_MODE);

        assertThat(descriptor.settingsUsed()).containsExactly(ProviderSetting.MODEL);
    }
}
