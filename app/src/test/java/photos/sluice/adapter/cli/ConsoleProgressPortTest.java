package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleProgressPortTest {

    private final ByteArrayOutputStream written = new ByteArrayOutputStream();

    private final ConsoleProgressPort progress = this.plain();

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

    @Test
    void aQuietRunReportsNoProgressAtAll() {
        this.progress.quiet(true);
        this.progress.phaseStarted("Sorting...");
        this.progress.tick("Sorting...", 1, 2);
        this.progress.phaseFinished("Sorting...");

        assertThat(this.written()).isEmpty();
    }

    @Test
    void aQuietRunStillSaysWhatItHeardTheCallerAskFor() {
        this.progress.quiet(true);
        this.progress.note("Stopping.");

        assertThat(this.lines()).containsExactly("Stopping.");
    }

    // A carriage return alone is what draws over a row, and on Windows every println already ends
    // in one. So what this looks for is the pair that rewrites: a return with the next row's text
    // straight after it.
    @Test
    void aRunNobodyIsWatchingNeverDrawsOverARowItAlreadyWrote() {
        this.progress.phaseStarted("Sorting...");
        this.progress.tick("Sorting...", 1, 2);

        assertThat(this.written()).doesNotContain("\rSorting");
    }

    @Test
    void aWatchedRunDrawsEveryCountOverTheSameRow() {
        final ConsoleProgressPort watched = this.redrawn();

        watched.phaseStarted("Sorting...");
        watched.tick("Sorting...", 1, 2);

        assertThat(this.written()).isEqualTo("\rSorting...\rSorting... 1/2");
    }

    @Test
    void aShorterCountBlanksWhatTheLongerOneLeftBehind() {
        final ConsoleProgressPort watched = this.redrawn();

        watched.tick("Sorting...", 1000, 2000);
        watched.tick("Sorting...", 1, 2);

        assertThat(this.written()).endsWith("\rSorting... 1/2        ");
    }

    @Test
    void aWatchedRunEndsItsRowBeforeSayingAnythingElse() {
        final ConsoleProgressPort watched = this.redrawn();

        watched.tick("Sorting...", 1, 2);
        watched.note("Stopping.");

        assertThat(this.written()).isEqualTo("\rSorting... 1/2" + System.lineSeparator()
                + "Stopping." + System.lineSeparator());
    }

    @Test
    void aWatchedRunWithNoRowOpenDoesNotOpenTheAnswerWithABlankLine() {
        final ConsoleProgressPort watched = this.redrawn();

        watched.note("Stopping.");

        assertThat(this.written()).isEqualTo("Stopping." + System.lineSeparator());
    }

    @Test
    void aWatchedPhaseEndsItsRowRatherThanSayingItIsDone() {
        final ConsoleProgressPort watched = this.redrawn();

        watched.phaseStarted("Sorting...");
        watched.phaseFinished("Sorting...");

        assertThat(this.written()).isEqualTo("\rSorting..." + System.lineSeparator());
    }

    private ConsoleProgressPort plain() {
        return new ConsoleProgressPort(this.stream(), false, everyEvent());
    }

    private ConsoleProgressPort redrawn() {
        return new ConsoleProgressPort(this.stream(), true, everyEvent());
    }

    // Paced to write everything, so what each assertion reads is the drawing rather than the clock
    // the reporter happened to run against.
    private static ProgressPace everyEvent() {
        return new ProgressPace(Duration.ZERO);
    }

    private PrintStream stream() {
        return new PrintStream(this.written, true, StandardCharsets.UTF_8);
    }

    private String written() {
        return this.written.toString(StandardCharsets.UTF_8);
    }

    private String[] lines() {
        return this.written().lines().toArray(String[]::new);
    }
}
