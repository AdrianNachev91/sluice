package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleProgressPortTest {

    private final ByteArrayOutputStream written = new ByteArrayOutputStream();

    private final ConsoleProgressPort progress =
            new ConsoleProgressPort(new PrintStream(this.written, true, StandardCharsets.UTF_8));

    @Test
    void aStartedPhaseIsAnnouncedByName() {
        this.progress.phaseStarted("Sorting...");

        assertThat(this.lines()).containsExactly("Sorting...");
    }

    @Test
    void aTickSaysHowFarThroughThePhaseTheJobIs() {
        this.progress.tick("Sorting...", 850, 1204);

        assertThat(this.lines()).containsExactly("Sorting... 850/1,204");
    }

    // Driven from a machine that groups with dots, because every CI runner already groups with
    // commas. Without this the assertion passes against a reporter that took the default locale,
    // and the choice the production code makes goes unpinned everywhere it runs.
    @Test
    void aCountIsPunctuatedTheWayTheSentencesAroundItAre() {
        final Locale machine = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            this.progress.tick("Sifting...", 1000000, 2000000);
        } finally {
            Locale.setDefault(machine);
        }

        assertThat(this.lines()).containsExactly("Sifting... 1,000,000/2,000,000");
    }

    @Test
    void aFinishedPhaseIsReportedAsDone() {
        this.progress.phaseFinished("Moving to library...");

        assertThat(this.lines()).containsExactly("Moving to library... done");
    }

    @Test
    void anEmptyPhaseStillOpensAndCloses() {
        this.progress.phaseStarted("Sorting...");
        this.progress.phaseFinished("Sorting...");

        assertThat(this.lines()).containsExactly("Sorting...", "Sorting... done");
    }

    private String[] lines() {
        return this.written.toString(StandardCharsets.UTF_8).lines().toArray(String[]::new);
    }
}
