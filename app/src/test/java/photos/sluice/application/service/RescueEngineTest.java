package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.port.in.NoteIsNotTextException;
import photos.sluice.application.port.in.RescueRoot;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.dating.DateSource;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.IndexEntry;
import photos.sluice.domain.rescue.RescueSummary;
import photos.sluice.domain.review.ReasonNotes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class RescueEngineTest {

    @Test
    void rescuesADatedFolderIntoSortedAndRemovesTheFolder(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.REVIEW, "2019-06");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.undated()).isZero();
        assertThat(summary.folderRemoved()).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isFalse();
    }

    @Test
    void rescuesAYearAndMonthFolderOutOfTheUnreviewableRoot(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Unreviewable/2019/06/IMG_1.jpg"), "keeper");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.UNREVIEWABLE, "2019/06");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Unreviewable/2019/06"))).isFalse();
    }

    @Test
    void aVideoLandsUnderVideosRatherThanPhotos(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/2019-06/CLIP.mp4"), "footage");

        rescueEngine(root, noDate(), noDate()).rescue(RescueRoot.REVIEW, "2019-06");

        assertThat(Files.exists(root.resolve("Sorted/Videos/2019/06/CLIP.mp4"))).isTrue();
    }

    @Test
    void aRescueWritesNoLibraryHashIndexRow(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");
        final var hashIndex = new CsvLibraryHashIndex(SettingsFixture.workingRoot(root));

        rescueEngine(root, noDate(), noDate()).rescue(RescueRoot.REVIEW, "2019-06");

        assertThat(hashIndex.load()).isEmpty();

        // The control. The same index against the same working root does record a row when
        // something appends one, so the empty reading above is the engine's doing.
        try (final HashIndexPort.Session session = hashIndex.openSession()) {
            session.append(new IndexEntry("abc123", root.resolve("Library/Photos/2019/06/IMG_1.jpg")));
        }
        assertThat(hashIndex.load()).containsOnlyKeys("abc123");
    }

    @Test
    void aFileNothingCanDateMovesToTheUndatedFolderRatherThanStayingPut(@TempDir final Path root) throws IOException {
        final Path source = root.resolve("Review/Food/IMG_1.jpg");
        writeFile(source, "keeper");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(summary.rescued()).isZero();
        assertThat(summary.undated()).isEqualTo(1);
        assertThat(summary.moved()).isEqualTo(1);
        assertThat(summary.folderRemoved()).isTrue();
        assertThat(Files.exists(source)).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Unsorted/IMG_1.jpg"))).isTrue();
    }

    @Test
    void aFolderNamingNoMonthStillDatesFromExif(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");

        final RescueSummary summary = rescueEngine(root, someDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.undated()).isZero();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"))).isTrue();
    }

    @Test
    void aCategoryFolderPutsAPhotoBackUnderTheMonthItsNoteRecorded(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");
        writeFile(root.resolve("Review/Food/_reasons.txt"), "IMG_1.jpg (2019-06) - a plate of food");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.undated()).isZero();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"))).isTrue();
    }

    @Test
    void aNotedMonthBeatsWhatExifSaysAboutTheSamePhoto(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");
        writeFile(root.resolve("Review/Food/_reasons.txt"), "IMG_1.jpg (2020-08-09) - a plate of food");

        rescueEngine(root, someDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(Files.exists(root.resolve("Sorted/Photos/2020/08/IMG_1.jpg"))).isTrue();
    }

    @Test
    void aNoteAReaderHasBrokenLeavesTheRestOfTheChainToAnswer(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");
        writeFile(root.resolve("Review/Food/_reasons.txt"), "IMG_1.jpg (sometime last summer) - a plate of food");

        rescueEngine(root, someDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"))).isTrue();
    }

    @Test
    void aNoteNamingAnotherPhotoDatesNeitherOfThem(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");
        writeFile(root.resolve("Review/Food/_reasons.txt"), "IMG_2.jpg (2020-08-09) - a plate of food");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(summary.undated()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Unsorted/IMG_1.jpg"))).isTrue();
    }

    @Test
    void aNearCopyGroupDatesItsRejectFromTheNoteNamedForTheKeeper(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"), "the original");
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg"), "the original");
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_2.jpg"), "reject");
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg.txt"),
                "IMG_1.jpg (2019-06) - kept, sharpest" + System.lineSeparator() + "IMG_2.jpg (2020-08) - blurred");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.DUPLICATES, "2019-06_beach");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.alreadyInSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2020/08/IMG_2.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Duplicates/2019-06_beach"))).isFalse();
    }

    @Test
    void theGroupFoldersOwnNameNeverStandsInForARejectsMonth(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_2.jpg"), "reject");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.DUPLICATES, "2019-06_beach");

        assertThat(summary.undated()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Unsorted/IMG_2.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/IMG_2.jpg"))).isFalse();
    }

    @Test
    void aFileWhoseOwnBytesAreAlreadyAtItsDestinationIsDeletedRatherThanPutBackTwice(@TempDir final Path root)
            throws IOException {
        writeFile(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"), "the original");
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg"), "the original");
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg.txt"), "IMG_1.jpg (2019-06) - kept, sharpest");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.DUPLICATES, "2019-06_beach");

        assertThat(summary.alreadyInSorted()).isEqualTo(1);
        assertThat(summary.moved()).isZero();
        assertThat(Files.exists(root.resolve("Duplicates/2019-06_beach"))).isFalse();
        assertThat(regularFileCount(root.resolve("Sorted"))).isEqualTo(1);
        assertThat(Files.readString(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"))).isEqualTo("the original");
    }

    @Test
    void aFileWhoseNameIsTakenAtTheDestinationBySomethingLongerStillMoves(@TempDir final Path root)
            throws IOException {
        writeFile(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"), "a longer, different photo");
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "different");

        final RescueSummary summary = rescueEngine(root, someDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.alreadyInSorted()).isZero();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/IMG_1 (2).jpg"))).isTrue();
    }

    @Test
    void aFileMatchingOnNameAndSizeButNotOnItsBytesStillMoves(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Sorted/Photos/2019/06/IMG_1.jpg"), "aaaaa");
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "bbbbb");

        final RescueSummary summary = rescueEngine(root, someDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.alreadyInSorted()).isZero();
        assertThat(Files.readString(root.resolve("Sorted/Photos/2019/06/IMG_1 (2).jpg"))).isEqualTo("bbbbb");
    }

    @Test
    void theAlreadyThereCheckIsAskedUnderEveryRoot(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Sorted/Unsorted/IMG_1.jpg"), "the original");
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "the original");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(summary.alreadyInSorted()).isEqualTo(1);
        assertThat(summary.moved()).isZero();
        assertThat(regularFileCount(root.resolve("Sorted"))).isEqualTo(1);
    }

    @Test
    void dissolvingAFolderDeletesThisAppsOwnNoteAndLeavesAReadersOwn(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg"), "the keeper's copy");
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_2.jpg"), "reject");
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg.txt"), "IMG_1.jpg (2019-06) - kept, sharpest"
                + System.lineSeparator() + "IMG_2.jpg (2020-08) - blurred");
        writeFile(root.resolve("Duplicates/2019-06_beach/my-own-notes.txt"),
                "ask my gf which 1 she likes, they all look the same to me");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.DUPLICATES, "2019-06_beach");

        // The reader's own file is still in there, so nothing could remove the folder around it.
        assertThat(summary.folderRemoved()).isFalse();
        assertThat(Files.exists(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg.txt"))).isFalse();
        assertThat(Files.exists(root.resolve("Duplicates/2019-06_beach/my-own-notes.txt"))).isTrue();
    }

    @Test
    void aNoteSurvivesTheDeletionOfThePhotoItIsNamedFor(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_2.jpg"), "reject");
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg.txt"), "IMG_1.jpg (2019-06) - kept, sharpest"
                + System.lineSeparator() + "IMG_2.jpg (2020-08) - blurred");

        final RescueSummary summary = rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.DUPLICATES, "2019-06_beach");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.folderRemoved()).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2020/08/IMG_2.jpg"))).isTrue();
    }

    @Test
    void eachFolderIsReadAgainstItsOwnNote(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Unreviewable/2019/06/IMG_1.jpg"), "one");
        writeFile(root.resolve("Unreviewable/2019/06/_reasons.txt"), "IMG_1.jpg (2021-01) - unjudgeable");
        writeFile(root.resolve("Unreviewable/2019/07/IMG_2.jpg"), "two");
        writeFile(root.resolve("Unreviewable/2019/07/_reasons.txt"), "IMG_2.jpg (2022-02) - unjudgeable");

        rescueEngine(root, noDate(), noDate()).rescue(RescueRoot.UNREVIEWABLE, "2019");

        assertThat(Files.exists(root.resolve("Sorted/Photos/2021/01/IMG_1.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2022/02/IMG_2.jpg"))).isTrue();
    }

    @Test
    void aStrayFileThatIsNotMediaMovesNowhereAndKeepsTheFolder(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");
        final Path stray = root.resolve("Review/Food/notes.docx");
        writeFile(stray, "typing");

        final RescueSummary summary = rescueEngine(root, someDate(), noDate()).rescue(RescueRoot.REVIEW, "Food");

        assertThat(summary.moved()).isEqualTo(1);
        assertThat(summary.folderRemoved()).isFalse();
        assertThat(Files.exists(stray)).isTrue();
    }

    @Test
    void aRescueStoppedWithFilesStillToReachSaysItStoppedShort(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "one");
        writeFile(root.resolve("Review/Food/IMG_2.jpg"), "two");

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final RescueSummary summary = rescueEngine(root, someDate(), someDate())
                .rescue(RescueRoot.REVIEW, "Food", cancelAfterFirstTick, cancelled::get);

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.cancelled()).isTrue();
        assertThat(summary.leftBehind()).isEqualTo(1);
    }

    // Stopped before the first file, so scan order cannot decide what is in the untouched tail: it
    // is the whole folder either way.
    @Test
    void aStoppedRescueCountsOnlyTheMediaItLeftBehind(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "one");
        writeFile(root.resolve("Review/Food/IMG_2.jpg"), "two");
        writeFile(root.resolve("Review/Food/_reasons.txt"), "IMG_1.jpg - low-res");
        writeFile(root.resolve("Review/Food/notes.docx"), "typing");

        final RescueSummary summary = rescueEngine(root, someDate(), someDate())
                .rescue(RescueRoot.REVIEW, "Food", ProgressCallback.NO_OP, () -> true);

        assertThat(summary.moved()).isZero();
        assertThat(summary.leftBehind()).isEqualTo(2);
    }

    @Test
    void aRescueWhoseStopArrivedAfterTheLastFileDissolvesTheFolderAnyway(@TempDir final Path root)
            throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "one");

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final RescueSummary summary = rescueEngine(root, someDate(), someDate())
                .rescue(RescueRoot.REVIEW, "Food", cancelAfterFirstTick, cancelled::get);

        assertThat(cancelled).isTrue();
        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.cancelled()).isFalse();
        assertThat(summary.folderRemoved()).isTrue();
        assertThat(Files.exists(root.resolve("Review/Food"))).isFalse();
    }

    @Test
    void namingTheRootItselfIsRejectedRatherThanEmptyingIt(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");

        for (final String naming : new String[] {"", ".", "Food/.."}) {
            assertThatThrownBy(() -> rescueEngine(root, someDate(), someDate())
                    .rescue(RescueRoot.REVIEW, naming))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.exists(root.resolve("Review"))).isTrue();
        assertThat(Files.exists(root.resolve("Review/Food/IMG_1.jpg"))).isTrue();
    }

    @Test
    void aTransferThatNeverLandedIsNeverMovedIntoSorted(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "whole");
        final Path part = root.resolve("Review/Food/IMG_2.jpg.sluice-part");
        writeFile(part, "half");

        final RescueSummary summary = rescueEngine(root, someDate(), someDate())
                .rescue(RescueRoot.REVIEW, "Food");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.cancelled()).isFalse();
        assertThat(Files.exists(part)).isTrue();
    }

    @Test
    void reasonsFileIsRemovedWhenTheWholeFolderDissolves(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");
        writeFile(root.resolve("Review/2019-06/_reasons.txt"), "IMG_1.jpg - low-res");

        rescueEngine(root, noDate(), noDate()).rescue(RescueRoot.REVIEW, "2019-06");

        assertThat(Files.exists(root.resolve("Review/2019-06"))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("unreadableNotes")
    void aNoteThatCannotBeReadStopsTheRescueRatherThanDatingFromWhatIsLeft(final UncheckedIOException failure,
                                                                          final Class<?> refusal,
                                                                          @TempDir final Path root)
            throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");
        writeFile(root.resolve("Review/Food/_reasons.txt"), "IMG_1.jpg (2020-08) - a plate of food");

        assertThatThrownBy(() -> rescueEngine(root, noDate(), noDate(), new UnreadableNote(failure))
                .rescue(RescueRoot.REVIEW, "Food"))
                .isInstanceOf(refusal);
        assertThat(Files.exists(root.resolve("Review/Food/IMG_1.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Review/Food/_reasons.txt"))).isTrue();
    }

    @Test
    void aNoteHoldingSomethingOtherThanTextNamesItself(@TempDir final Path root) throws IOException {
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_2.jpg"), "reject");
        writeFile(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg.txt"), "IMG_1.jpg (2019-06) - kept, sharpest");
        final var store = new UnreadableNote("IMG_1.jpg.txt",
                new UncheckedIOException(new MalformedInputException(1)));

        assertThatThrownBy(() -> rescueEngine(root, noDate(), noDate(), store)
                .rescue(RescueRoot.DUPLICATES, "2019-06_beach"))
                .isInstanceOfSatisfying(NoteIsNotTextException.class, named ->
                        assertThat(named.file())
                                .isEqualTo(root.resolve("Duplicates/2019-06_beach/IMG_1.jpg.txt")));
    }

    @Test
    void aFolderWhoseNoteIsItsOnlyDateKeepsItsPhotosWhenThatNoteCannotBeRead(@TempDir final Path root)
            throws IOException {
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");
        writeFile(root.resolve("Review/Food/_reasons.txt"), "IMG_1.jpg (2020-08) - a plate of food");
        final var store = new UnreadableNote(new UncheckedIOException(new MalformedInputException(1)));

        assertThatThrownBy(() -> rescueEngine(root, noDate(), noDate(), store)
                .rescue(RescueRoot.REVIEW, "Food"))
                .isInstanceOf(NoteIsNotTextException.class);
        assertThat(Files.exists(root.resolve("Sorted/Unsorted/IMG_1.jpg"))).isFalse();
        assertThat(Files.exists(root.resolve("Review/Food"))).isTrue();
    }

    @Test
    void escapingTheRootIsRejected(@TempDir final Path root) {
        assertThatThrownBy(() -> rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.REVIEW, "../Sorted"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void escapingTheUnreviewableRootIsRejectedTheSameWay(@TempDir final Path root) {
        assertThatThrownBy(() -> rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.UNREVIEWABLE, "../Review"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCrashAfterTheFirstMoveLeavesTheSecondFileWhereItWasAndTheFolderStanding(@TempDir final Path root)
            throws IOException {
        final Path first = root.resolve("Review/2019-06/a.jpg");
        final Path second = root.resolve("Review/2019-06/b.jpg");
        writeFile(first, "keeper1");
        writeFile(second, "keeper2");
        final RescueEngine crashingEngine = rescueEngine(root, noDate(), noDate(), new FailingAfterMoves(1));

        assertThatThrownBy(() -> crashingEngine.rescue(RescueRoot.REVIEW, "2019-06"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash");

        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/a.jpg"))).isTrue();
        assertThat(Files.exists(second)).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/b.jpg"))).isFalse();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isTrue();

        final RescueSummary resumeSummary = rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.REVIEW, "2019-06");

        assertThat(resumeSummary.rescued()).isEqualTo(1);
        assertThat(resumeSummary.folderRemoved()).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/b.jpg"))).isTrue();
    }

    @Test
    void progressCallbackTicksOnceForEveryFileWhereverItLands(@TempDir final Path root) throws IOException {
        // "Food" names no month, so each file's destination depends solely on dateForOnly. One
        // lands under a year, the other undated. A dated folder would differ: its own name would
        // date both whatever the DateSource says.
        writeFile(root.resolve("Review/Food/dated.jpg"), "keeper");
        writeFile(root.resolve("Review/Food/undated.jpg"), "no date");

        final List<String> ticks = new ArrayList<>();
        final RescueSummary summary = rescueEngine(root, dateForOnly("dated.jpg"), noDate())
                .rescue(RescueRoot.REVIEW, "Food", (current, total) -> ticks.add(current + "/" + total));

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.undated()).isEqualTo(1);
        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    @Test
    void cancelMidRescueStopsEarlyLeavingMovedFilesMovedAndTheFolderIntact(@TempDir final Path root)
            throws IOException {
        writeFile(root.resolve("Review/2019-06/a.jpg"), "a");
        writeFile(root.resolve("Review/2019-06/b.jpg"), "b");
        final Path reasonsFile = root.resolve("Review/2019-06/_reasons.txt");
        writeFile(reasonsFile, "b.jpg - low-res");

        // Cancels once the first entry has ticked, so the loop stops before the other two are even
        // looked at. Scan order across the three entries isn't guaranteed, so which one ticks first
        // varies; every assertion below holds regardless of which it is.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final RescueSummary summary = rescueEngine(root, noDate(), noDate())
                .rescue(RescueRoot.REVIEW, "2019-06", cancelAfterFirstTick, cancelled::get);

        assertThat(summary.moved()).isBetween(0, 1);
        assertThat(summary.folderRemoved()).isFalse();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isTrue();
        // The actual claim: a pass that stopped early must not be treated as safe to dissolve. It
        // would otherwise delete this marker with two of the three entries never reached.
        assertThat(Files.exists(reasonsFile)).isTrue();
        assertThat(regularFileCount(root.resolve("Review/2019-06"))).isEqualTo(3 - summary.moved());
        assertThat(regularFileCount(root.resolve("Sorted"))).isEqualTo(summary.moved());
    }

    // regularFileCount()'s own regression test: the cancellation test above calls it on the Sorted
    // root, which NioMediaStore only creates as a side effect of an actual move. When scan order
    // puts _reasons.txt first, nothing moves before cancellation and Sorted never exists on disk. A
    // bare Files.walk() throws NoSuchFileException in exactly that case.
    @Test
    void regularFileCountTreatsAMissingDirectoryAsZeroFilesInsteadOfThrowing(@TempDir final Path root)
            throws IOException {
        assertThat(regularFileCount(root.resolve("never-created"))).isZero();
    }

    // Treats a missing root as zero files rather than throwing NoSuchFileException. Callers may
    // pass a directory that a test scenario never ends up creating.
    private static long regularFileCount(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (final Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    // Only the damaged one can name the file, the other having been raised by something that never
    // opened it.
    private static Stream<Arguments> unreadableNotes() {
        return Stream.of(
                arguments(new UncheckedIOException(new MalformedInputException(1)),
                        NoteIsNotTextException.class),
                arguments(new UncheckedIOException(new AccessDeniedException("_reasons.txt")),
                        UncheckedIOException.class));
    }

    private static DateSource noDate() {
        return (_, _) -> Optional.empty();
    }

    private static DateSource someDate() {
        return (_, _) -> Optional.of(LocalDateTime.of(2019, 6, 15, 12, 0));
    }

    private static DateSource dateForOnly(final String filename) {
        return (file, _) -> file.path().getFileName().toString().equals(filename)
                ? Optional.of(LocalDateTime.of(2019, 6, 15, 12, 0))
                : Optional.empty();
    }

    private static RescueEngine rescueEngine(final Path repoRoot, final DateSource exifSource,
                                             final DateSource filenameSource) {
        return rescueEngine(repoRoot, exifSource, filenameSource, new NioMediaStore());
    }

    private static RescueEngine rescueEngine(final Path repoRoot, final DateSource exifSource,
                                             final DateSource filenameSource, final MediaStore mediaStore) {
        final var pathsConfig = SettingsFixture.pathsConfig(repoRoot, repoRoot.resolve("Library"),
                repoRoot.resolve("Inbox"));
        final var rescueDateResolver = new RescueDateResolver(exifSource, filenameSource);
        return new RescueEngine(pathsConfig, mediaStore, rescueDateResolver, new Sha256Hasher());
    }

    private static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // A real store in every respect but one: the note in a folder cannot be read. Which failure it
    // reports is the whole point, the port's contract splitting damaged bytes from a file nobody
    // reached.
    private static final class UnreadableNote extends NioMediaStore {
        private final String refusing;
        private final UncheckedIOException failure;

        UnreadableNote(final UncheckedIOException failure) {
            this(ReasonNotes.FILE_NAME, failure);
        }

        UnreadableNote(final String refusing, final UncheckedIOException failure) {
            this.refusing = refusing;
            this.failure = failure;
        }

        @Override
        public List<String> readLines(final Path file) {
            if (file.getFileName().toString().equals(this.refusing)) {
                throw this.failure;
            }
            return super.readLines(file);
        }
    }

    // Wraps the real NioMediaStore but throws after a fixed number of successful move() calls,
    // which is RescueEngine's own move step. Deterministically simulates a crash mid-run.
    // listFiles() sorts the delegate's result so which file counts as "first" doesn't depend on
    // filesystem walk order.
    private static final class FailingAfterMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();

        private int movesUntilFailure;

        FailingAfterMoves(final int movesUntilFailure) {
            this.movesUntilFailure = movesUntilFailure;
        }

        @Override
        public Walk listFilesTolerating(final Path root) {
            return this.delegate.listFilesTolerating(root);
        }

        @Override
        public List<Path> listFiles(final Path root) {
            return this.delegate.listFiles(root).stream().sorted().toList();
        }

        @Override
        public List<Path> listChildDirectories(final Path root) {
            return this.delegate.listChildDirectories(root);
        }

        @Override
        public Instant lastModifiedTime(final Path path) {
            return this.delegate.lastModifiedTime(path);
        }

        @Override
        public Optional<Path> realDirectory(final Path path) {
            return this.delegate.realDirectory(path);
        }

        @Override
        public Path realFile(final Path path) {
            return this.delegate.realFile(path);
        }

        @Override
        public Path move(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress watching) {
            if (this.movesUntilFailure <= 0) {
                throw new RuntimeException("simulated crash");
            }
            this.movesUntilFailure--;
            return this.delegate.move(source, destDir, stop, watching);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress watching) {
            return this.delegate.moveTo(source, destination, stop, watching);
        }

        @Override
        public Path copy(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress watching) {
            return this.delegate.copy(source, destDir, stop, watching);
        }

        @Override
        public Path copyTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress watching) {
            return this.delegate.copyTo(source, destination, stop, watching);
        }

        @Override
        public void delete(final Path path) {
            this.delegate.delete(path);
        }

        @Override
        public void ensureDirectory(final Path dir) {
            this.delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(final Path path) {
            return this.delegate.exists(path);
        }

        @Override
        public long size(final Path path) {
            return this.delegate.size(path);
        }

        @Override
        public void appendLine(final Path file, final String line) {
            this.delegate.appendLine(file, line);
        }

        @Override
        public void write(final Path file, final String content) {
            this.delegate.write(file, content);
        }

        @Override
        public List<String> readLines(final Path file) {
            return this.delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(final Path root) {
            this.delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(final Path dir) {
            this.delegate.removeIfEmptyOfFiles(dir);
        }
    }
}
