package photos.sluice.adapter.ui;

import javafx.application.ColorScheme;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeTest {

    @Test
    void aDarkDesktopGetsTheDarkLook() {
        assertThat(Theme.matching(ColorScheme.DARK)).isEqualTo(Theme.DARK);
    }

    @Test
    void aLightDesktopGetsTheLightLook() {
        assertThat(Theme.matching(ColorScheme.LIGHT)).isEqualTo(Theme.LIGHT);
    }

    @Test
    void everyLookIsBuiltOnTheBaseSheetBeforeAnythingElse() {
        for (final Theme theme : Theme.values()) {
            assertThat(theme.sheets()).first().isEqualTo("/ui/sluice.css");
        }
    }

    @Test
    void onlyTheDarkLookLayersASheetOverTheBase() {
        assertThat(Theme.LIGHT.sheets()).containsExactly("/ui/sluice.css");
        assertThat(Theme.DARK.sheets()).containsExactly("/ui/sluice.css", "/ui/sluice-dark.css");
    }

    @Test
    void everySheetEveryLookNamesIsInTheBuild() {
        for (final Theme theme : Theme.values()) {
            for (final String sheet : theme.sheets()) {
                assertThat(Theme.class.getResource(sheet))
                        .describedAs("stylesheet %s named by %s", sheet, theme)
                        .isNotNull();
            }
        }
    }

    // Everything that mentions the overlay calls it colour-only. Nothing made that true until this.
    // A padding landing in it would let the two looks differ over something no reader expects.
    //
    // Three JavaFX look properties join the app's own -sluice-* tokens: -fx-base, -fx-background
    // and -fx-control-inner-background. JavaFX's own default look derives every stock control's
    // colour from those three, so a stock TextField or Spinner ignores the tokens above entirely.
    // They are colours too, which is what this test actually guards - a size or spacing property
    // still fails it.
    private static final Set<String> ALLOWED_NON_TOKEN_PROPERTIES =
            Set.of("-fx-base", "-fx-background", "-fx-control-inner-background");

    @Test
    void theDarkOverlayRedefinesColoursAndNothingElse() throws Exception {
        final var declarations = Pattern.compile("^\\s*(-[a-zA-Z-]+)\\s*:", Pattern.MULTILINE)
                .matcher(sheetText("/ui/sluice-dark.css"))
                .results()
                .map(match -> match.group(1))
                .toList();

        assertThat(declarations).isNotEmpty().allSatisfy(property ->
                assertThat(property.startsWith("-sluice-") || ALLOWED_NON_TOKEN_PROPERTIES.contains(property))
                        .describedAs("'%s' is neither a -sluice- token nor an allowed JavaFX look colour", property)
                        .isTrue());
    }

    private static String sheetText(final String sheet) throws Exception {
        try (final var stream = Theme.class.getResourceAsStream(sheet)) {
            return new String(Objects.requireNonNull(stream, sheet).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
