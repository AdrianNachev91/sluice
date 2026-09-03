package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class VisionProviderDescriptorTest {

    private static final SecretId KEY = new SecretId("a-provider", "A_PROVIDER_KEY");
    private static final ModelCatalog MODELS =
            new ModelCatalog(List.of(new ModelOption("a-model", "A model")), "a-model");

    @Test
    void aProviderNamingItsOwnCredentialAndUsingOneIsAccepted() {
        assertThatCode(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.MODEL, ProviderSetting.CREDENTIAL), Set.of(ProviderSetting.MODEL), KEY,
                MODELS, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aProviderTakingNoCredentialAndNamingNoneIsAccepted() {
        assertThatCode(() -> new VisionProviderDescriptor("no-key", "No key",
                Set.of(ProviderSetting.ENDPOINT), Set.of(), null, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void requiringASettingItDoesNotUseIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(), Set.of(ProviderSetting.MODEL), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-provider");
    }

    @Test
    void usingACredentialWithoutNamingOneIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.CREDENTIAL), Set.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namingACredentialWithoutUsingOneIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.MODEL), Set.of(), KEY, MODELS, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namingAnotherProvidersCredentialIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("second-provider", "Second provider",
                Set.of(ProviderSetting.CREDENTIAL), Set.of(), KEY, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-provider");
    }

    @Test
    void theSetsItAnswersWithAreItsOwn() {
        final var mutable = new HashSet<>(Set.of(ProviderSetting.MODEL));
        final var descriptor =
                new VisionProviderDescriptor("a-provider", "A provider", mutable, Set.of(), null, MODELS, null, null);

        mutable.add(ProviderSetting.ENDPOINT);

        assertThat(descriptor.settingsUsed()).containsExactly(ProviderSetting.MODEL);
    }

    @Test
    void usingAModelWithoutOfferingACatalogIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.MODEL), Set.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-provider");
    }

    @Test
    void offeringACatalogWithoutUsingAModelIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(), Set.of(), null, MODELS, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-provider");
    }

    @Test
    void namingADefaultEndpointWithoutUsingAnEndpointSettingIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(), Set.of(), null, null, "https://example.test", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-provider");
    }

    @Test
    void aProviderUsingAnEndpointSettingMayNameADefaultForIt() {
        assertThatCode(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.ENDPOINT), Set.of(), null, null, "https://example.test", null))
                .doesNotThrowAnyException();
    }

    @Test
    void namingASetupGuideWithoutUsingACredentialIsRefused() {
        assertThatThrownBy(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(), Set.of(), null, null, null, "Get one at example.test."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a-provider");
    }

    @Test
    void aProviderUsingACredentialMayNameWhereToGetOne() {
        assertThatCode(() -> new VisionProviderDescriptor("a-provider", "A provider",
                Set.of(ProviderSetting.CREDENTIAL), Set.of(), KEY, null, null, "Get one at example.test."))
                .doesNotThrowAnyException();
    }
}
