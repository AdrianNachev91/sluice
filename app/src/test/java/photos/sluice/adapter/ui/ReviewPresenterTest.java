package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.ReviewView.Action;
import photos.sluice.adapter.ui.ReviewView.FolderCard;
import photos.sluice.adapter.ui.ReviewView.Group;
import photos.sluice.adapter.ui.ReviewView.Kind;
import photos.sluice.adapter.ui.ReviewView.Notes;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.RescueRoot;
import photos.sluice.application.port.in.ReviewListing;
import photos.sluice.application.port.in.ReviewListing.FiledBy;
import photos.sluice.application.port.in.ReviewListing.Folder;
import photos.sluice.application.port.in.ReviewListing.Root;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.rescue.RescueSummary;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewPresenterTest {

    private static final Path WORKING_ROOT = Path.of("W:", "Sluice");

    @Test
    void eachKindOfFolderGetsItsOwnSectionAndTheyReadInAFixedOrder() {
        final ReviewPresenter presenter = over(folder(Root.UNREVIEWABLE, "2019/06"),
                folder(Root.DUPLICATES, "2019-06_beach"), heldBack("2019-06"),
                folder(Root.REVIEW, "Food"), folder(Root.REVIEW, "junk"));

        assertThat(presenter.view().groups()).extracting(Group::heading)
                .containsExactly("General junk", "Junk by category", "A sift never sees these",
                        "Near-copies", "A sift could not judge these");
    }

    @Test
    void theFolderSluiceSuppliesItselfReadsApartFromTheOnesAReaderNamedAReasonFor() {
        final ReviewPresenter presenter =
                over(folder(Root.REVIEW, "Food"), folder(Root.REVIEW, "junk"));

        final List<Group> groups = presenter.view().groups();
        assertThat(groups).extracting(Group::heading)
                .containsExactly("General junk", "Junk by category");
        assertThat(groups.getFirst().folders()).extracting(FolderCard::name)
                .containsExactly("junk");
        assertThat(groups.getLast().folders()).extracting(FolderCard::name)
                .containsExactly("Food");
    }

    @Test
    void aSortsOwnFoldersReadUnderTheirOwnHeadingRatherThanAmongTheCategories() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"), heldBack("Unsorted"));

        assertThat(presenter.view().groups()).extracting(Group::heading)
                .containsExactly("Junk by category", "A sift never sees these");
        assertThat(presenter.view().groups().getLast().folders()).extracting(FolderCard::name)
                .containsExactly("Unsorted");
    }

    // The sections are a fixed list, so a folder whose pair nobody listed would vanish from the
    // screen with nothing said about it.
    @Test
    void everyFolderTheListingCarriesReachesASection() {
        final List<Folder> all = List.of(folder(Root.REVIEW, "Food"), heldBack("Unsorted"),
                folder(Root.DUPLICATES, "2019-06_beach"), folder(Root.UNREVIEWABLE, "2019/06"));
        final ReviewPresenter presenter = over(all.toArray(new Folder[0]));

        assertThat(presenter.view().groups().stream().mapToInt(group -> group.folders().size()).sum())
                .isEqualTo(all.size());
    }

    // The pair below is one no walk produces, and the point is that the screen does not lie about
    // it. Answered off the sections, the line would say nothing is waiting over a folder that is.
    @Test
    void aFolderNoSectionClaimsIsStillNotReportedAsNothingWaiting() {
        final ReviewPresenter presenter = over(folder(Root.DUPLICATES, FiledBy.A_SORT, "made-by-hand"));

        assertThat(presenter.view().nothingYet()).isNull();
    }

    @Test
    void aKindWithNothingWaitingGetsNoSection() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"));

        assertThat(presenter.view().groups()).extracting(Group::heading)
                .containsExactly("Junk by category");
    }

    @Test
    void aCardSaysWhatIsInTheFolderAndWhenItLastChanged() {
        final ReviewPresenter presenter = over(new Folder(Root.REVIEW, FiledBy.A_SORT, "2019-06",
                WORKING_ROOT.resolve("Review").resolve("2019-06"), 37, 2, Instant.now()));

        final FolderCard card = onlyCard(presenter);

        assertThat(card.name()).isEqualTo("2019-06");
        assertThat(card.held()).isEqualTo("37 photos and 2 videos");
        assertThat(card.age()).isEqualTo("Last changed less than an hour ago");
    }

    @Test
    void aFolderNobodyCouldStatIsAgedAsNotKnownRatherThanAsADateIn1970() {
        final ReviewPresenter presenter = over(new Folder(Root.REVIEW, FiledBy.A_SIFT, "Food",
                WORKING_ROOT.resolve("Review").resolve("Food"), 1, 0, Instant.EPOCH));

        assertThat(onlyCard(presenter).age()).isEqualTo("When it last changed is not known");
    }

    @Test
    void everyRootOffersTheRescue() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"), heldBack("Unsorted"),
                folder(Root.DUPLICATES, "2019-06_beach"), folder(Root.UNREVIEWABLE, "2019/06"));

        assertThat(kindsOffered(presenter, 0)).containsExactly(Kind.OPEN, Kind.RESCUE);
        assertThat(kindsOffered(presenter, 1)).containsExactly(Kind.OPEN, Kind.RESCUE);
        assertThat(kindsOffered(presenter, 2)).containsExactly(Kind.OPEN, Kind.RESCUE);
        assertThat(kindsOffered(presenter, 3)).containsExactly(Kind.OPEN, Kind.RESCUE);
    }

    @Test
    void eachRescueCarriesTheRootItsOwnFolderSitsUnder() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"),
                folder(Root.DUPLICATES, "2019-06_beach"), folder(Root.UNREVIEWABLE, "2019/06"));

        assertThat(actionsOn(presenter, 0).getLast().root()).isEqualTo(RescueRoot.REVIEW);
        assertThat(actionsOn(presenter, 1).getLast().root()).isEqualTo(RescueRoot.DUPLICATES);
        assertThat(actionsOn(presenter, 2).getLast().root()).isEqualTo(RescueRoot.UNREVIEWABLE);
    }

    @Test
    void openFolderIsTheWayOnFromACardAndTheRescueIsNot() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"),
                folder(Root.DUPLICATES, "2019-06_beach"));

        assertThat(actionsOn(presenter, 0)).extracting(Action::kind, Action::leading)
                .containsExactly(tuple(Kind.OPEN, true), tuple(Kind.RESCUE, false));
        assertThat(actionsOn(presenter, 1)).extracting(Action::kind, Action::leading)
                .containsExactly(tuple(Kind.OPEN, true), tuple(Kind.RESCUE, false));
    }

    @Test
    void theQuestionBeforeARescueNamesTheFolderAndPromisesNoCountAndNoRemoval() {
        final ReviewPresenter presenter = over(new Folder(Root.REVIEW, FiledBy.A_SIFT, "Food",
                WORKING_ROOT.resolve("Review").resolve("Food"), 9, 0, Instant.now()));

        final Action move = actionsOn(presenter, 0).getLast();

        assertThat(requireNonNull(move.confirm()).heading()).isEqualTo("Rescue Food to Sorted?");
        assertThat(requireNonNull(move.confirm()).detail())
                .isEqualTo("The photos and videos still in it go back into Sorted. A photo Sorted "
                        + "already holds is deleted from the folder rather than put back twice.");
    }

    @Test
    void movingAFolderHandsTheFolderToTheFacadeAndTakesTheReaderToTheDashboard() {
        final Pipeline pipeline = pipeline();
        final JobHandle<RescueSummary> started = handle();
        doReturn(started).when(pipeline).rescue(RescueRoot.REVIEW, "Food");
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "Food"));
        final var opened = new AtomicInteger();
        presenter.setOpenDashboard(opened::incrementAndGet);

        presenter.rescue(actionsOn(presenter, 0).getLast());

        verify(pipeline).rescue(RescueRoot.REVIEW, "Food");
        assertThat(opened).hasValue(1);
    }

    @Test
    void aMoveThatCouldNotStartSaysSoAndLeavesTheReaderOnThisScreen() {
        final Pipeline pipeline = pipeline();
        doReturn(handle()).when(pipeline).rescue(any(), any());
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "Food"),
                folder(Root.REVIEW, "junk"));
        final var opened = new AtomicInteger();
        presenter.setOpenDashboard(opened::incrementAndGet);
        // The launcher takes one job at a time, so the first press occupies it and the second is
        // the window the screen's own button gate cannot see.
        presenter.rescue(actionsOn(presenter, 0).getLast());

        presenter.rescue(actionsOn(presenter, 1).getLast());

        assertThat(opened).hasValue(1);
        assertThat(requireNonNull(presenter.view().message()).text())
                .isEqualTo("Something else started running just now, and only one job runs at a "
                        + "time. Nothing was moved. Try again once it finishes.");
    }

    // The reader is sent to the dashboard on a move that took, and comes back to this screen after.
    // A refusal left standing from an earlier press would greet them beside a folder that moved.
    @SuppressWarnings("unchecked")
    @Test
    void aMoveThatTookClearsWhateverAnEarlierRefusalSaid() {
        final Pipeline pipeline = pipeline();
        final var firstJob = new CompletableFuture<RescueSummary>();
        final JobHandle<RescueSummary> running = mock(JobHandle.class);
        when(running.onComplete()).thenReturn(firstJob);
        doReturn(running).when(pipeline).rescue(any(), any());
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "Food"),
                folder(Root.REVIEW, "junk"));
        presenter.setOpenDashboard(() -> { });
        presenter.rescue(actionsOn(presenter, 0).getLast());
        presenter.rescue(actionsOn(presenter, 1).getLast());
        assertThat(presenter.view().message()).isNotNull();

        firstJob.complete(new RescueSummary(1, 0, 0, 0, true, false));
        presenter.rescue(actionsOn(presenter, 1).getLast());

        assertThat(presenter.view().message()).isNull();
    }

    @Test
    void nothingIsWaitingIsSaidInWordsWhereThereAreNoFoldersAtAll() {
        final ReviewPresenter presenter = over();

        assertThat(presenter.view().nothingYet())
                .isEqualTo("Nothing is waiting for you. When a sort or a sift puts photos "
                        + "somewhere for you to look at, they show here.");
    }

    @Test
    void aRootThatCouldNotBeReadIsNamedRatherThanReadAsNothingWaiting() {
        final Pipeline pipeline = pipeline();
        doReturn(new ReviewListing(List.of(), List.of(WORKING_ROOT.resolve("Duplicates"))))
                .when(pipeline).reviewListing();
        final ReviewPresenter presenter = presenterOver(pipeline);

        assertThat(presenter.view().nothingYet()).isNull();
        assertThat(presenter.view().unreadable())
                .isEqualTo("What is waiting in " + WORKING_ROOT.resolve("Duplicates")
                        + " is unknown, because it cannot be read. Opening it yourself is the "
                        + "quickest way to find out why.");
    }

    // The app takes one job at a time whoever started it, so the press would be refused after its
    // question had already been answered.
    @Test
    void aFolderStillShowsItsMoveWhileSomethingElseIsRunning() {
        final Pipeline pipeline = pipeline();
        when(pipeline.isBusy()).thenReturn(true);
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "Food"));

        assertThat(kindsOffered(presenter, 0)).containsExactly(Kind.OPEN, Kind.RESCUE);
        assertThat(actionsOn(presenter, 0)).extracting(Action::kind, Action::live)
                .containsExactly(tuple(Kind.OPEN, true), tuple(Kind.RESCUE, false));
    }

    @Test
    void bothOfAFoldersButtonsArePressableWhileNothingIsRunning() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"));

        assertThat(actionsOn(presenter, 0)).extracting(Action::live).containsOnly(true);
    }

    @Test
    void aReadThatFailedOutrightIsNotAlsoReportedAsNothingWaiting() {
        final Pipeline pipeline = pipeline();
        doThrow(new PathsMisconfiguredException(List.of())).when(pipeline).reviewListing();
        final ReviewPresenter presenter = presenterOver(pipeline);

        assertThat(presenter.view().nothingYet()).isNull();
    }

    // Left standing, each would offer to move a count the failed reading can no longer vouch for.
    @Test
    void theFoldersFromAnEarlierReadingAreGoneOnceAReadingFails() {
        final Pipeline pipeline = pipeline();
        doReturn(new ReviewListing(List.of(folder(Root.REVIEW, "Food")), List.of()))
                .when(pipeline).reviewListing();
        final ReviewPresenter presenter = presenterOver(pipeline);
        assertThat(presenter.view().groups()).hasSize(1);

        doThrow(new PathsMisconfiguredException(List.of())).when(pipeline).reviewListing();
        presenter.refresh();

        assertThat(presenter.view().groups()).isEmpty();
    }

    @Test
    void aFolderNoSiftCouldJudgeCarriesTheSameFoldAsEveryOtherRoot() {
        final ReviewPresenter presenter = over(folder(Root.UNREVIEWABLE, "2019/06"));

        assertThat(onlyCard(presenter).notes()).isNotNull();
    }

    @Test
    void aReadThatFailedOutrightIsReportedInWordsRatherThanAsABug() {
        final Pipeline pipeline = pipeline();
        doThrow(new PathsMisconfiguredException(List.of())).when(pipeline).reviewListing();
        final ReviewPresenter presenter = presenterOver(pipeline);

        assertThat(requireNonNull(presenter.view().message()).refused()).isTrue();
        assertThat(requireNonNull(presenter.view().message()).text()).isNotBlank();
    }

    @Test
    void aFoldStaysShutUntilItIsPressedAndReadsNothingWhileItIs() {
        final Pipeline pipeline = pipeline();
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "Food"));

        assertThat(notesOn(presenter).shown()).isFalse();
        verify(pipeline, never()).reviewNotes(any());
    }

    @Test
    void anOpenedFoldShowsWhatSluiceWroteAboutThatFolder() {
        final Pipeline pipeline = pipeline();
        when(pipeline.reviewNotes(any())).thenReturn(List.of("a.jpg - blurry", "b.jpg - low-res"));
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "Food"));

        presenter.toggleNotes(onlyCard(presenter).path());

        assertThat(notesOn(presenter).shown()).isTrue();
        assertThat(notesOn(presenter).lines())
                .containsExactly("a.jpg - blurry", "b.jpg - low-res");
        assertThat(notesOn(presenter).nothingWritten()).isNull();
    }

    @Test
    void aFoldOverMoreLinesThanItDrawsCutsThemAndSaysWhereTheRestAre() {
        final Pipeline pipeline = pipeline();
        final List<String> written = IntStream.rangeClosed(1, 250)
                .mapToObj(i -> "photo-" + i + ".jpg - too small to sift")
                .toList();
        when(pipeline.reviewNotes(any())).thenReturn(written);
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "2019-06"));

        presenter.toggleNotes(onlyCard(presenter).path());

        assertThat(notesOn(presenter).lines()).hasSize(200);
        assertThat(notesOn(presenter).lines().getFirst()).isEqualTo(written.getFirst());
        assertThat(notesOn(presenter).lines().getLast()).isEqualTo(written.get(199));
        assertThat(notesOn(presenter).beyondTheFold())
                .isEqualTo("50 more lines are in the note file itself.");
    }

    @Test
    void aFoldOverOneMoreLineThanItDrawsCountsThatOneInTheSingular() {
        final Pipeline pipeline = pipeline();
        when(pipeline.reviewNotes(any())).thenReturn(IntStream.rangeClosed(1, 201)
                .mapToObj(n -> "IMG_" + n + ".jpg - too small to sift")
                .toList());
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "2019-06"));

        presenter.toggleNotes(onlyCard(presenter).path());

        assertThat(notesOn(presenter).beyondTheFold())
                .isEqualTo("1 more line is in the note file itself.");
    }

    @Test
    void aFoldWithLinesInItPointsAtTheFolderTheyAreIn() {
        final Pipeline pipeline = pipeline();
        when(pipeline.reviewNotes(any())).thenReturn(List.of("a.jpg - blurry"));
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "Food"));

        presenter.toggleNotes(onlyCard(presenter).path());

        assertThat(notesOn(presenter).beyondTheFold()).isNull();
        assertThat(notesOn(presenter).openTheFolder())
                .isEqualTo("Open the folder to see all the notes.");
    }

    @Test
    void aFoldOverAFolderNothingWasWrittenAboutPointsAtNoFolder() {
        final Pipeline pipeline = pipeline();
        when(pipeline.reviewNotes(any())).thenReturn(List.of());
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "Food"));

        presenter.toggleNotes(onlyCard(presenter).path());

        assertThat(notesOn(presenter).nothingWritten())
                .isEqualTo("Nothing was written about this folder.");
        assertThat(notesOn(presenter).openTheFolder()).isNull();
    }

    @Test
    void aFoldOverExactlyWhatItDrawsSaysNothingAboutMoreLines() {
        final Pipeline pipeline = pipeline();
        final List<String> written = IntStream.rangeClosed(1, 200)
                .mapToObj(i -> "photo-" + i + ".jpg - too small to sift")
                .toList();
        when(pipeline.reviewNotes(any())).thenReturn(written);
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "2019-06"));

        presenter.toggleNotes(onlyCard(presenter).path());

        assertThat(notesOn(presenter).lines()).isEqualTo(written);
    }

    @Test
    void theScreenSaysNothingIsWaitingOnlyOnceAReadHasLanded() {
        final Pipeline pipeline = pipeline();
        doReturn(new ReviewListing(List.of(), List.of())).when(pipeline).reviewListing();
        final var presenter = new ReviewPresenter(pipeline,
                new RunLauncherPresenter(pipeline, new FxProgressPort()));

        assertThat(presenter.view().nothingYet()).isEqualTo("Looking at what is waiting...");

        presenter.refresh();

        assertThat(presenter.view().nothingYet()).startsWith("Nothing is waiting for you.");
    }

    // The read runs off the thread that paints, so two folds can be in flight at once. A slow read
    // landing last must not publish its lines under whichever fold is open by then.
    @Test
    void aSlowReadLandingAfterItsOwnFoldWasShutPublishesNothing() throws Exception {
        final Pipeline pipeline = pipeline();
        final Folder junk = folder(Root.REVIEW, "junk");
        final Folder food = folder(Root.REVIEW, "Food");
        final var junkMayFinish = new CountDownLatch(1);
        final var junkHasStarted = new CountDownLatch(1);
        when(pipeline.reviewNotes(junk.path())).thenAnswer(_ -> {
            junkHasStarted.countDown();
            assertThat(junkMayFinish.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of("IMG_0001.jpg - a photo of a monitor");
        });
        when(pipeline.reviewNotes(food.path())).thenReturn(List.of("IMG_0231.jpg - a plate of food"));
        final ReviewPresenter presenter = over(pipeline, junk, food);

        final Thread slow = Thread.ofVirtual().start(() -> presenter.toggleNotes(junk.path()));
        assertThat(junkHasStarted.await(10, TimeUnit.SECONDS)).isTrue();
        // Shuts junk while its own read is still running, and opens another fold beside it.
        presenter.toggleNotes(junk.path());
        presenter.toggleNotes(food.path());
        junkMayFinish.countDown();
        slow.join();

        assertThat(notesOn(presenter, "junk").shown()).isFalse();
        assertThat(notesOn(presenter, "Food").shown()).isTrue();
        assertThat(notesOn(presenter, "Food").lines())
                .containsExactly("IMG_0231.jpg - a plate of food");
    }

    // A note off a large backlog takes a moment to read. So the second press of a double-click
    // lands while the first is still reading, with the fold not yet showing anything.
    @Test
    void pressingAFoldTwiceWhileItIsStillReadingLeavesItShut() throws Exception {
        final Pipeline pipeline = pipeline();
        final Folder junk = folder(Root.REVIEW, "junk");
        final var mayFinish = new CountDownLatch(1);
        final var hasStarted = new CountDownLatch(1);
        when(pipeline.reviewNotes(junk.path())).thenAnswer(_ -> {
            hasStarted.countDown();
            assertThat(mayFinish.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of("IMG_0001.jpg - a photo of a monitor");
        });
        final ReviewPresenter presenter = over(pipeline, junk);

        final Thread first = Thread.ofVirtual().start(() -> presenter.toggleNotes(junk.path()));
        assertThat(hasStarted.await(10, TimeUnit.SECONDS)).isTrue();
        presenter.toggleNotes(junk.path());
        mayFinish.countDown();
        first.join();

        assertThat(notesOn(presenter).shown()).isFalse();
    }

    @Test
    void pressingAnOpenFoldAgainShutsIt() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"));
        presenter.toggleNotes(onlyCard(presenter).path());

        presenter.toggleNotes(onlyCard(presenter).path());

        assertThat(notesOn(presenter).shown()).isFalse();
    }

    @Test
    void openingASecondFoldLeavesTheFirstOpen() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"),
                folder(Root.REVIEW, "Scenery"));
        presenter.toggleNotes(cardsIn(presenter, 0).getFirst().path());

        presenter.toggleNotes(cardsIn(presenter, 0).getLast().path());

        assertThat(cardsIn(presenter, 0)).extracting(card -> requireNonNull(card.notes()).shown())
                .containsExactly(true, true);
    }

    @Test
    void shuttingOneFoldLeavesEveryOtherAsItWas() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"),
                folder(Root.REVIEW, "Scenery"));
        presenter.toggleNotes(cardsIn(presenter, 0).getFirst().path());
        presenter.toggleNotes(cardsIn(presenter, 0).getLast().path());

        presenter.toggleNotes(cardsIn(presenter, 0).getFirst().path());

        assertThat(cardsIn(presenter, 0)).extracting(card -> requireNonNull(card.notes()).shown())
                .containsExactly(false, true);
    }

    @Test
    void aFolderNothingWasWrittenAboutSaysSoRatherThanOpeningOnNothing() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"));

        presenter.toggleNotes(onlyCard(presenter).path());

        assertThat(notesOn(presenter).nothingWritten())
                .isEqualTo("Nothing was written about this folder.");
    }

    @Test
    void notesThatCouldNotBeReadSayThatRatherThanReadingAsNoneWritten() {
        final Pipeline pipeline = pipeline();
        when(pipeline.reviewNotes(any())).thenThrow(new PathsMisconfiguredException(List.of()));
        final ReviewPresenter presenter = over(pipeline, folder(Root.REVIEW, "Food"));

        presenter.toggleNotes(onlyCard(presenter).path());

        assertThat(notesOn(presenter).nothingWritten())
                .isEqualTo("What was written about this folder could not be read.");
    }

    @Test
    void anOpenFoldIsShutByTheNextReadingOfTheFolders() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"));
        presenter.toggleNotes(onlyCard(presenter).path());

        presenter.refresh();

        assertThat(notesOn(presenter).shown()).isFalse();
    }

    @Test
    void theButtonRescuingAFolderIsCalledWhateverTheRescueModeIsCalled() {
        final ReviewPresenter presenter = over(folder(Root.REVIEW, "Food"));

        assertThat(actionsOn(presenter, 0).getLast().label()).isEqualTo(RunMode.RESCUE.label());
    }

    private static List<Kind> kindsOffered(final ReviewPresenter presenter, final int group) {
        return actionsOn(presenter, group).stream().map(Action::kind).toList();
    }

    private static List<Action> actionsOn(final ReviewPresenter presenter, final int group) {
        return cardsIn(presenter, group).getFirst().actions();
    }

    private static List<FolderCard> cardsIn(final ReviewPresenter presenter, final int group) {
        return presenter.view().groups().get(group).folders();
    }

    private static Notes notesOn(final ReviewPresenter presenter) {
        return requireNonNull(onlyCard(presenter).notes());
    }

    private static Notes notesOn(final ReviewPresenter presenter, final String name) {
        return requireNonNull(presenter.view().groups().stream()
                .flatMap(group -> group.folders().stream())
                .filter(card -> card.name().equals(name))
                .findFirst()
                .orElseThrow()
                .notes());
    }

    private static FolderCard onlyCard(final ReviewPresenter presenter) {
        assertThat(presenter.view().groups()).hasSize(1);
        assertThat(presenter.view().groups().getFirst().folders()).hasSize(1);
        return presenter.view().groups().getFirst().folders().getFirst();
    }

    private static Folder folder(final Root root, final String name) {
        return folder(root, FiledBy.A_SIFT, name);
    }

    private static Folder heldBack(final String name) {
        return folder(Root.REVIEW, FiledBy.A_SORT, name);
    }

    private static Folder folder(final Root root, final FiledBy filedBy, final String name) {
        return new Folder(root, filedBy, name, WORKING_ROOT.resolve(root.name()).resolve(name), 1, 0,
                Instant.now());
    }

    private static ReviewPresenter over(final Folder... folders) {
        return over(pipeline(), folders);
    }

    private static ReviewPresenter over(final Pipeline pipeline, final Folder... folders) {
        doReturn(new ReviewListing(List.of(folders), List.of())).when(pipeline).reviewListing();
        return presenterOver(pipeline);
    }

    private static ReviewPresenter presenterOver(final Pipeline pipeline) {
        final var presenter = new ReviewPresenter(pipeline,
                new RunLauncherPresenter(pipeline, new FxProgressPort()));
        presenter.refresh();
        return presenter;
    }

    // A mock answers reviewNotes with null, which no real read can do. Empty is what a folder
    // carrying no note actually answers, so that is what the ones not about notes get.
    private static Pipeline pipeline() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.reviewNotes(any())).thenReturn(List.of());
        return pipeline;
    }

    @SuppressWarnings("unchecked")
    private static JobHandle<RescueSummary> handle() {
        final JobHandle<RescueSummary> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(new CompletableFuture<>());
        return handle;
    }
}
