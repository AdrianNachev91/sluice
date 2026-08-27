package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.RunResultView.CardAction;
import photos.sluice.adapter.ui.RunResultView.Count;
import photos.sluice.adapter.ui.RunResultView.Tone;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.imports.ImportSummary;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// Every way a job can end, read straight rather than through a started job. The presenter's own
// test covers the endings a screen reaches by pressing something. These are the arms a run has to
// actually produce, and a hand-built summary reaches all of them.
class RunResultsTest {

    private static final Path PREP_DIR = Path.of("logs", "sift-prep", "2019");

    @Test
    void aSiftBlockedByItsOwnValidationSaysNothingWasMoved() {
        final RunResultView card = RunResults.of(RunMode.SIFT, new CullJobOutcome.Blocked(
                waitingJob(new ShardTally(28, 28, 28)), List.of(new Finding.MissingMontageField("montage-003")),
                CullReport.nothingSpent("anthropic", 0), null));

        assertThat(card.heading()).isEqualTo("Sifting stopped and needs a look.");
        assertThat(card.tone()).isEqualTo(Tone.UNFINISHED);
        assertThat(requireNonNull(card.detail())).contains("Nothing was moved");
        assertThat(card.action()).isNull();
    }

    @Test
    void aBlockedSiftStillAccountsForTheSheetsItJudged() {
        final RunResultView card = RunResults.of(RunMode.SIFT, new CullJobOutcome.Blocked(
                waitingJob(new ShardTally(28, 28, 28)), List.of(),
                CullReport.nothingSpent("anthropic", 0), null));

        assertThat(labelled(card, "Sheets judged")).isEqualTo("28 of 28");
    }

    @Test
    void aSiftCancelledBeforeItsSheetsWereBuiltCountsNoneOfThem() {
        final RunResultView card = RunResults.of(RunMode.SIFT,
                new CullJobOutcome.Cancelled(CullReport.nothingSpent("anthropic", 0), null));

        assertThat(card.heading()).isEqualTo("Sifting was cancelled.");
        assertThat(card.tone()).isEqualTo(Tone.UNFINISHED);
        assertThat(card.counts()).isEmpty();
    }

    // The card below offers to continue and this one cannot, so the two must not share a sentence.
    // They did: both said "You can continue at any time", and this one has no button to press.
    @Test
    void aSiftCancelledBeforeItsSheetsWereBuiltPromisesNoWayToContinue() {
        final RunResultView card = RunResults.of(RunMode.SIFT,
                new CullJobOutcome.Cancelled(CullReport.nothingSpent("anthropic", 0), null));

        assertThat(card.action()).isNull();
        assertThat(card.detail()).isEqualTo("No sheets were built yet.");
    }

    // Its sheets are built and some are judged, so the door back in stays open. What it must not do
    // is start again on its own.
    @Test
    void aSiftTheUserStoppedOffersToContinueRatherThanLeavingThemToFindTheWayBack() {
        final RunResultView card = RunResults.of(RunMode.SIFT, waiting(WaitingReason.CANCELLED));

        assertThat(card.heading()).isEqualTo("Sifting was cancelled.");
        assertThat(card.action()).isEqualTo(new CardAction.ContinueRun(
                "You can continue at any time.", "Continue sifting", PREP_DIR));
    }

    @Test
    void aFinishedSortOffersToSiftTheOneTimelineItFilled() {
        final RunResultView card = RunResults.of(RunMode.SORT, sortSummary(List.of()));

        assertThat(card.action()).isEqualTo(new CardAction.SiftNow("Sift 2019", 2019, 2));
    }

    @Test
    void aSortWithSomethingToSiftSaysNothingUnderItsHeading() {
        final RunResultView card = RunResults.of(RunMode.SORT, sortSummary(List.of()));

        assertThat(card.detail()).isNull();
    }

    @Test
    void aSortThatFiledNothingOffersNoSift() {
        final RunResultView card = RunResults.of(RunMode.SORT, sortedInto(Set.of()));

        assertThat(card.action()).isNull();
    }

    @Test
    void aSortThatSetEveryFileAsideSaysWhyAndWhereTheyWent() {
        final RunResultView card = RunResults.of(RunMode.SORT, allOf(39, "lowRes"));

        assertThat(card.detail())
                .isEqualTo("Nothing ended up in Sorted, so there is nothing to sift yet. Their "
                        + "file size or their resolution is under what a sift looks at, so they "
                        + "are in Review instead.");
    }

    @Test
    void aSortThatCouldDateNothingSaysThereIsNoYearToFileThemUnder() {
        final RunResultView card = RunResults.of(RunMode.SORT, allOf(12, "unsorted"));

        assertThat(requireNonNull(card.detail()))
                .contains("Not one of them carries a date that can be trusted");
    }

    @Test
    void aSortThatFoundEverythingAlreadyInTheLibrarySaysSo() {
        final RunResultView card = RunResults.of(RunMode.SORT, allOf(7, "reimports"));

        assertThat(requireNonNull(card.detail())).contains("in your library already");
    }

    // Naming one bucket where several took a share would describe part of the run as the whole.
    @Test
    void aSortWhoseFilesWentSeveralWaysLeavesTheReasonToTheRows() {
        final RunResultView card = RunResults.of(RunMode.SORT,
                new SortSummary(20, 0, 0, 0, 0, 12, 8, 0, List.of(), List.of(), Set.of(), List.of()));

        assertThat(requireNonNull(card.detail()))
                .endsWith("The rows below say what became of each one.");
    }

    @Test
    void aSortWithNothingInScopeSaysSoRatherThanNamingRowsItHasNone() {
        final RunResultView card = RunResults.of(RunMode.SORT,
                new SortSummary(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), Set.of(), List.of()));

        assertThat(card.detail()).isEqualTo("Nothing in your Inbox was ready to sort.");
    }

    @Test
    void aSortSpanningSeveralTimelinesOffersNoSiftRatherThanPickingOne() {
        final RunResultView card = RunResults.of(RunMode.SORT, sortedInto(Set.of(2019, 2020)));

        assertThat(card.action()).isNull();
    }

    @Test
    void aRescueCountsWhatItMovedAndWhatItLeftBehind() {
        final RunResultView card = RunResults.of(RunMode.RESCUE,
                new RescueSummary(12, List.of("a.jpg", "b.jpg"), true));

        assertThat(card.heading()).isEqualTo("Rescuing finished.");
        assertThat(labelled(card, "Moved to your library")).isEqualTo("12");
        assertThat(labelled(card, "Left behind")).isEqualTo("2");
    }

    @Test
    void aRescueThatLeftNothingBehindDrawsNoRowSayingSo() {
        final RunResultView card = RunResults.of(RunMode.RESCUE,
                new RescueSummary(12, List.of(), true));

        assertThat(card.counts()).extracting(Count::label).containsExactly("Moved to your library");
    }

    @Test
    void aMoveThatReachedNoPartOfTheLibraryStillSaysSoRatherThanShowingNothing() {
        final RunResultView card = RunResults.of(RunMode.MOVE_TO_LIBRARY,
                new CommitSummary(0, Map.of()));

        assertThat(card.counts()).extracting(Count::label, Count::value)
                .containsExactly(tuple("Moved to your library", "0"));
    }

    @Test
    void aFileUnderNoPartOfTheLibraryThisAppFilesIntoIsStillCounted() {
        final RunResultView card = RunResults.of(RunMode.MOVE_TO_LIBRARY,
                new CommitSummary(4, Map.of(LibraryBucket.OTHER, 4)));

        assertThat(labelled(card, "Elsewhere in your library")).isEqualTo("4");
    }

    // Nothing produces this today. What it proves is that meeting one leaves the reader a heading
    // and a way off the card, rather than a screen with no way off it.
    @Test
    void anOutcomeThisCardCannotReadStillSaysTheWorkFinishedAndOffersDone() {
        final RunResultView card = RunResults.of(RunMode.SORT, new Object());

        assertThat(card.heading()).isEqualTo("Sorting finished.");
        assertThat(card.tone()).isEqualTo(Tone.FINISHED);
        assertThat(card.doneLabel()).isEqualTo("Done");
    }

    @Test
    void aJobThatEndedWithNoResultAtAllStillSaysTheWorkFinished() {
        final RunResultView card = RunResults.of(RunMode.SORT, null);

        assertThat(card.heading()).isEqualTo("Sorting finished.");
        assertThat(card.counts()).isEmpty();
    }

    @Test
    void everyEndingOfASiftThatArchivedAPriorRecordSaysWhatBecameOfIt() {
        final Path archived = Path.of("logs", "archives", "2019-2026-08-24_22-01-33");

        assertThat(RunResults.of(RunMode.SIFT, new CullJobOutcome.Blocked(
                waitingJob(new ShardTally(28, 28, 28)), List.of(),
                CullReport.nothingSpent("anthropic", 0), archived)).archived()).isNotNull();
        assertThat(RunResults.of(RunMode.SIFT, new CullJobOutcome.Cancelled(
                CullReport.nothingSpent("anthropic", 0), archived)).archived()).isNotNull();
    }

    @Test
    void everyWayAnImportedPhotoEndsUpGetsItsOwnRow() {
        final RunResultView card = RunResults.of(RunMode.IMPORT,
                new ImportSummary(1204, 1190, 4, 2, 3, 5, false));

        assertThat(card.counts()).extracting(Count::label, Count::value).containsExactly(
                tuple("Imported", "1,190"),
                tuple("Skipped: already in your Inbox", "4"),
                tuple("Could not be read", "3"),
                tuple("Arrived broken", "2"),
                tuple("Folders could not be opened", "5"));
    }

    // The four counts a row is drawn for only when it is non-zero, against a clean import that has
    // none of them.
    @Test
    void anImportThatWentPerfectlyDrawsOnlyTheRowSayingSo() {
        final RunResultView card = RunResults.of(RunMode.IMPORT,
                new ImportSummary(1204, 1204, 0, 0, 0, 0, false));

        assertThat(card.counts()).extracting(Count::label).containsExactly("Imported");
    }

    @Test
    void anImportThatLeftPhotosBehindSaysSoInItsHeading() {
        final RunResultView card = RunResults.of(RunMode.IMPORT,
                new ImportSummary(1204, 1195, 0, 2, 7, 0, false));

        assertThat(card.heading()).isEqualTo("Importing finished, with 9 left behind.");
    }

    // A folder it could not open is not a photo left behind. Counting one would head almost every
    // card import as gone wrong, since a Windows-formatted card always carries one.
    @Test
    void aFolderItCouldNotOpenLeavesTheHeadingAlone() {
        final RunResultView card = RunResults.of(RunMode.IMPORT,
                new ImportSummary(1204, 1204, 0, 0, 0, 3, false));

        assertThat(card.heading()).isEqualTo("Importing finished.");
    }

    @Test
    void aTroubledImportStillCarriesNoWarningStripe() {
        final RunResultView card = RunResults.of(RunMode.IMPORT,
                new ImportSummary(1204, 1190, 4, 2, 3, 5, false));

        assertThat(card.warning()).isNull();
    }

    private static String labelled(final RunResultView card, final String label) {
        return card.counts().stream()
                .filter(count -> count.label().equals(label))
                .map(Count::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row labelled " + label + " on " + card.counts()));
    }

    private static CullJobOutcome waiting(final WaitingReason reason) {
        return new CullJobOutcome.Waiting(waitingJob(new ShardTally(0, 0, 28)), reason,
                CullReport.nothingSpent("anthropic", 28), null);
    }

    private static WaitingCullJob waitingJob(final ShardTally sheets) {
        return new WaitingCullJob("2019", PREP_DIR, sheets, Instant.EPOCH);
    }

    private static SortSummary sortSummary(final List<String> warnings) {
        return new SortSummary(3, 0, 0, 2, 1, 0, 0, 0, List.of(), List.of(), Set.of(2019), warnings);
    }

    private static SortSummary allOf(final int files, final String bucket) {
        return new SortSummary(files,
                "reimports".equals(bucket) ? files : 0,
                0, 0, 0,
                "lowRes".equals(bucket) ? files : 0,
                "unsorted".equals(bucket) ? files : 0,
                0, List.of(), List.of(), Set.of(), List.of());
    }

    private static SortSummary sortedInto(final Set<Integer> years) {
        return new SortSummary(3, 0, 0, 2, 1, 0, 0, 0, List.of(), List.of(), years, List.of());
    }
}
