package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.SiftOptions;
import photos.sluice.application.port.out.SiftReport;
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.ModelOption;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.application.port.out.VisionSieve;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.sift.PrepDir;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisteredVisionProvidersTest {

    private static final VisionProviderDescriptor WAITING = describing("waiting", "Zebra");
    private static final VisionProviderDescriptor CALLING = describing("calling", "Aardvark");

    // Handed in back to front, so an implementation answering in injection order fails. A dropdown
    // built from this would otherwise reshuffle whenever the wiring did.
    @Test
    void ordersProvidersByLabelRatherThanByHowTheyWereInjected() {
        final var catalog = new RegisteredVisionProviders(sieves(WAITING, CALLING));

        assertThat(catalog.providers()).extracting(VisionProviderDescriptor::id)
                .containsExactly("calling", "waiting");
    }

    @Test
    void twoProvidersSharingAnIdAreRefused() {
        final var clash = describing("waiting", "A different label");

        assertThatThrownBy(() -> new RegisteredVisionProviders(sieves(WAITING, clash)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("waiting");
    }

    @Test
    void findsAProviderByItsOwnId() {
        final var catalog = new RegisteredVisionProviders(sieves(WAITING, CALLING));

        assertThat(catalog.byId("waiting")).contains(WAITING);
        assertThat(catalog.byId("nothing-registered")).isEmpty();
    }

    @Test
    void asksTheProviderTheIdNames() {
        final var catalog = new RegisteredVisionProviders(sieves(WAITING, CALLING));

        assertThat(catalog.check("calling")).isEqualTo(new ProviderCheck.Refused("calling"));
    }

    @Test
    void checkingAnUnregisteredIdIsRefusedNamingWhatIsRegistered() {
        final var catalog = new RegisteredVisionProviders(sieves(WAITING, CALLING));

        assertThatThrownBy(() -> catalog.check("nothing-registered"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing-registered")
                .hasMessageContaining("waiting");
    }

    private static VisionProviderDescriptor describing(final String id, final String label) {
        return new VisionProviderDescriptor(id, label, Set.of(ProviderSetting.MODEL), Set.of(), null,
                new ModelCatalog(List.of(new ModelOption("a-model", "A model")), null), null, null);
    }

    private static List<VisionSieve> sieves(final VisionProviderDescriptor... descriptors) {
        return Stream.of(descriptors).map(RegisteredVisionProvidersTest::sifting).toList();
    }

    private static VisionSieve sifting(final VisionProviderDescriptor descriptor) {
        return new VisionSieve() {
            @Override
            public VisionProviderDescriptor describe() {
                return descriptor;
            }

            @Override
            public ProviderType type() {
                return ProviderType.API;
            }

            // Answers with its own id, so a test can tell which sieve the catalog reached.
            @Override
            public ProviderCheck check() {
                return new ProviderCheck.Refused(descriptor.id());
            }

            @Override
            public SpendForecast forecast(final PrepDir prep) {
                return new SpendForecast.NoSpend();
            }

            @Override
            public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
                throw new AssertionError("the catalog does not sift");
            }
        };
    }
}
