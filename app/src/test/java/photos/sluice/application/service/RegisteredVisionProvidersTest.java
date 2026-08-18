package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.cull.PrepDir;

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
        final var catalog = new RegisteredVisionProviders(cullers(WAITING, CALLING));

        assertThat(catalog.providers()).extracting(VisionProviderDescriptor::id)
                .containsExactly("calling", "waiting");
    }

    @Test
    void twoProvidersSharingAnIdAreRefused() {
        final var clash = describing("waiting", "A different label");

        assertThatThrownBy(() -> new RegisteredVisionProviders(cullers(WAITING, clash)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("waiting");
    }

    @Test
    void findsAProviderByItsOwnId() {
        final var catalog = new RegisteredVisionProviders(cullers(WAITING, CALLING));

        assertThat(catalog.byId("waiting")).contains(WAITING);
        assertThat(catalog.byId("nothing-registered")).isEmpty();
    }

    private static VisionProviderDescriptor describing(final String id, final String label) {
        return new VisionProviderDescriptor(id, label, Set.of(ProviderSetting.MODEL), Set.of(), null);
    }

    private static List<VisionCuller> cullers(final VisionProviderDescriptor... descriptors) {
        return Stream.of(descriptors).map(RegisteredVisionProvidersTest::culling).toList();
    }

    private static VisionCuller culling(final VisionProviderDescriptor descriptor) {
        return new VisionCuller() {
            @Override
            public VisionProviderDescriptor describe() {
                return descriptor;
            }

            @Override
            public ProviderType type() {
                return ProviderType.API;
            }

            @Override
            public CullReport cull(final PrepDir prep, final CullOptions opts) {
                throw new AssertionError("the catalog does not cull");
            }
        };
    }
}
