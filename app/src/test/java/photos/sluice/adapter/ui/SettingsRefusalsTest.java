package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.out.MalformedSettingsException;
import photos.sluice.application.port.out.UnusableSettingsException;
import photos.sluice.application.port.out.WorkingRootBusyException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// Each test asks that the sentence names its own condition. That is what tells somebody whether to
// wait, to go and look at a file, or to give up.
class SettingsRefusalsTest {

    private static final String NOT_KNOWN_WHY = "It's not known why";

    private static final String SETTINGS_NOT_SAVED = "Your settings were not saved.";

    private static final Path CONFIG = Path.of("AppData", "Sluice", "config.yml");

    // The carry-through arms all pass under an implementation that answers getMessage() for
    // everything. What rules that out is the composed arms below, so these are only half the check.
    @Test
    void aRunHoldingTheSlotSaysToFinishItFirst() {
        assertThat(worded(new JobInProgressException("Something is running now. Finish it first.")))
                .isEqualTo("Something is running now. Finish it first.");
    }

    @Test
    void anotherSluiceHoldingTheWorkingRootSaysWhichProblemItIs() {
        final String said = worded(new WorkingRootBusyException(Path.of("D:", "Photos", "Sluice")));

        assertThat(said).doesNotContain(NOT_KNOWN_WHY).contains("running");
    }

    @Test
    void anAppOnItsWayOutSaysThatRatherThanNothing() {
        assertThat(worded(new ShuttingDownException("Sluice is closing. This was not saved.")))
                .doesNotContain(NOT_KNOWN_WHY);
    }

    @Test
    void aSettingTheAppWillNotRunOnSaysWhatIsWrongWithIt() {
        assertThat(worded(new UnusableSettingsException("More than the 12 categories allowed.")))
                .isEqualTo("More than the 12 categories allowed.");
    }

    @Test
    void aSettingsFileThatCannotBeUnderstoodNamesTheFileToGoAndLookAt() {
        final String said = worded(new MalformedSettingsException(CONFIG, "not valid YAML at line 4"));

        assertThat(said)
                .doesNotContain(NOT_KNOWN_WHY)
                .contains(CONFIG.toString())
                .contains("Nothing you had configured has changed");
    }

    @Test
    void aFileTheFilesystemWouldNotGiveUpSaysToTryAgain() {
        final String said = worded(new UncheckedIOException(new IOException("held by another process")));

        assertThat(said).doesNotContain(NOT_KNOWN_WHY).contains("Try again");
    }

    @Test
    void anythingUnforeseenIsWordedInThisAppsVoiceAndQuotesTheRest() {
        final String said = worded(new IllegalStateException("writeAndApply: NoSuchFileException"));

        assertThat(said)
                .startsWith("Your settings were not saved")
                .contains(NOT_KNOWN_WHY)
                .contains("NoSuchFileException");
    }

    // The opening sentence is the caller's whole, so a singular subject reads as well as a plural
    // one. Nothing after it refers back, which is what stops "Your theme" landing in "the file they
    // are stored in".
    @Test
    void eachScreenNamesWhatItWasSavingAndSaysTheRestTheSameWay() {
        final var thrown = new UncheckedIOException(new IOException("held by another process"));
        final String settings = worded(thrown);
        final String tail = settings.substring(SETTINGS_NOT_SAVED.length());

        assertThat(tail).isNotBlank();
        assertThat(SettingsRefusals.wordedForAUser(thrown, "Your photo categories were not saved."))
                .isEqualTo("Your photo categories were not saved." + tail);
        assertThat(SettingsRefusals.wordedForAUser(thrown, "Your theme was not saved."))
                .isEqualTo("Your theme was not saved." + tail);
    }

    private static String worded(final RuntimeException refusal) {
        return SettingsRefusals.wordedForAUser(refusal, SETTINGS_NOT_SAVED);
    }
}
