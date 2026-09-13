package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.sift.SiftCategory;
import photos.sluice.domain.sift.MontageConfig;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The ceilings on the free text these value types carry. Each has a control that stops a reader
// typing past it, and each is the half a config file or a non-interactive caller cannot walk past.
class SettingsBoundsTest {

    private static final PathSettings ROOTS = new PathSettings("repo", "library", "inbox");

    @Test
    void aModelIdPastItsCeilingIsRefused() {
        final String tooLong = "m".repeat(SiftProviderSettings.maxModel() + 1);

        assertThatThrownBy(() -> new SiftProviderSettings(tooLong, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model id");
        assertThat(new SiftProviderSettings("m".repeat(SiftProviderSettings.maxModel()), null, null).model())
                .hasSize(SiftProviderSettings.maxModel());
    }

    @Test
    void anEndpointPastItsCeilingIsRefused() {
        final String tooLong = "e".repeat(SiftProviderSettings.maxEndpoint() + 1);

        assertThatThrownBy(() -> new SiftProviderSettings(null, tooLong, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void anUnsetProviderStaysLegal() {
        assertThat(SiftProviderSettings.unset().model()).isNull();
        assertThat(SiftProviderSettings.unset().endpoint()).isNull();
    }

    // Each root is named in its own message, since a refusal that only says "a path" leaves the
    // reader checking all three.
    @Test
    void eachFolderRootIsBoundedAndNamedWhenItIsRefused() {
        final String tooLong = "p".repeat(PathSettings.maxRoot() + 1);

        assertThatThrownBy(() -> new PathSettings(tooLong, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("working root");
        assertThatThrownBy(() -> new PathSettings(null, tooLong, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("library root");
        assertThatThrownBy(() -> new PathSettings(null, null, tooLong))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inbox");
        assertThat(new PathSettings("p".repeat(PathSettings.maxRoot()), null, null).workingRoot())
                .hasSize(PathSettings.maxRoot());
    }

    @Test
    void anUnconfiguredInstallStaysLegal() {
        assertThat(new PathSettings(null, null, null).workingRoot()).isNull();
    }

    @Test
    void moreCategoriesThanOneInstallMayHoldIsRefused() {
        final List<SiftCategory> tooMany = IntStream.rangeClosed(0, Settings.maxCategories())
                .mapToObj(i -> SiftCategory.of("card-" + i, "description " + i)).toList();

        assertThatThrownBy(() -> settingsWith(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("photo categories");
    }

    @Test
    void aSetAtTheCeilingIsAccepted() {
        final List<SiftCategory> atTheLimit = IntStream.range(0, Settings.maxCategories())
                .mapToObj(i -> SiftCategory.of("card-" + i, "description " + i)).toList();

        assertThat(settingsWith(atTheLimit).categories()).hasSize(Settings.maxCategories());
    }

    @Test
    void twoCategoriesUnderOneNameAreRefusedAndTheRefusalNamesIt() {
        assertThatThrownBy(() -> settingsWith(List.of(
                SiftCategory.of("food", "meals"), SiftCategory.of("food", "also meals"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once: food.");
    }

    @Test
    void aCategoryCalledJunkIsRefusedWhereverItWasWritten() {
        assertThatThrownBy(() -> settingsWith(List.of(SiftCategory.of("junk", "mine"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may not hold one called 'junk'");
        assertThat(settingsWith(List.of(SiftCategory.of("junky", "not that one"))).categories())
                .hasSize(1);
    }

    private static Settings settingsWith(final List<SiftCategory> categories) {
        return new Settings(ROOTS, "anthropic", Map.of(), categories,
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);
    }
}
