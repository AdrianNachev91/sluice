package photos.sluice.adapter.ui.view;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.FxProgressPort;
import photos.sluice.adapter.ui.ReviewPresenter;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.application.port.in.ReviewListing;
import photos.sluice.application.port.in.ReviewListing.FiledBy;
import photos.sluice.application.port.in.ReviewListing.Folder;
import photos.sluice.application.port.in.ReviewListing.Root;
import photos.sluice.application.service.Pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// What only a built scene graph can be wrong about. Which sections and cards the screen draws, and
// what a press on one of them reaches.
class ReviewPaneTest {

    private static final Path WORKING_ROOT = Path.of("W:", "Sluice");

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
        FileManager.clear();
    }

    @Test
    void eachKindOfFolderIsDrawnAsItsOwnSectionWithItsFoldersInside() throws Exception {
        final Parent pane = onFxThread(() -> built(folder(Root.REVIEW, "Food"),
                folder(Root.DUPLICATES, "2019-06_beach")));

        assertThat(headings(pane, ".review-group-heading"))
                .containsExactly("Junk by category", "Near-copies");
        assertThat(headings(pane, ".review-card-name")).containsExactly("Food", "2019-06_beach");
    }

    @Test
    void theTwoSectionsUnderReviewAreDrawnApartWithTheirOwnFoldersUnderEach() throws Exception {
        final Parent pane = onFxThread(() -> built(folder(Root.REVIEW, "Food"),
                heldBack("Unsorted")));

        assertThat(headings(pane, ".review-group-heading"))
                .containsExactly("Junk by category", "A sift never sees these");
        assertThat(headings(pane, ".review-card-name")).containsExactly("Food", "Unsorted");
    }

    @Test
    void aFolderUnderAnyRootDrawsBothButtons() throws Exception {
        final Parent pane = onFxThread(() -> built(folder(Root.REVIEW, "Food"),
                folder(Root.DUPLICATES, "2019-06_beach"), folder(Root.UNREVIEWABLE, "2019/06")));

        assertThat(pane.lookup("#review-open-review-food")).isNotNull();
        assertThat(pane.lookup("#review-move-review-food")).isNotNull();
        assertThat(pane.lookup("#review-open-duplicates-2019-06-beach")).isNotNull();
        assertThat(pane.lookup("#review-move-duplicates-2019-06-beach")).isNotNull();
        assertThat(pane.lookup("#review-open-unreviewable-2019-06")).isNotNull();
        assertThat(pane.lookup("#review-move-unreviewable-2019-06")).isNotNull();
    }

    @Test
    void aRescueIsDrawnInactiveWhileSomethingElseIsRunning() throws Exception {
        final Pipeline pipeline = pipeline(folder(Root.REVIEW, "Food"));
        when(pipeline.isBusy()).thenReturn(true);

        final Parent pane = onFxThread(() -> built(pipeline));

        assertThat(pane.lookup("#review-move-review-food").isDisabled()).isTrue();
        assertThat(pane.lookup("#review-open-review-food").isDisabled()).isFalse();
    }

    @Test
    void bothButtonsArePressableWhileNothingIsRunning() throws Exception {
        final Parent pane = onFxThread(() -> built(folder(Root.REVIEW, "Food")));

        assertThat(pane.lookup("#review-move-review-food").isDisabled()).isFalse();
        assertThat(pane.lookup("#review-open-review-food").isDisabled()).isFalse();
    }

    @Test
    void openHandsThatFoldersOwnPathToTheFileManager() throws Exception {
        final var opened = new AtomicReference<@Nullable Path>(null);
        FileManager.openWith(opened::set);
        final Parent pane = onFxThread(() -> built(folder(Root.REVIEW, "Food")));

        onFxThread(() -> press(pane, "#review-open-review-food"));

        assertThat(opened).hasValue(WORKING_ROOT.resolve("REVIEW").resolve("Food"));
    }

    // A render and a test both build this screen with no file manager to hand, so an unset opener
    // is a state to tolerate rather than refuse.
    @Test
    void openDoesNothingWhereNothingIsSetToOpenAFolder() throws Exception {
        final var opened = new AtomicReference<@Nullable Path>(null);
        FileManager.openWith(opened::set);
        FileManager.clear();
        final Parent pane = onFxThread(() -> built(folder(Root.REVIEW, "Food")));

        assertThat(onFxThread(() -> press(pane, "#review-open-review-food"))).isTrue();

        assertThat(opened).hasNullValue();
    }

    // It runs on a virtual thread with nothing above it, so a throw would be lost rather than
    // reported. A folder gone since its card was drawn is the likeliest way in.
    @Test
    void aFolderThatHasGoneSinceItsCardWasDrawnThrowsNothing(@TempDir final Path gone)
            throws Exception {
        Files.delete(gone);

        assertThatCode(() -> FileManager.inTheSystemFileManager(gone)).doesNotThrowAnyException();
    }

    @Test
    void aShutFoldDrawsNoneOfWhatSluiceWrote() throws Exception {
        final Parent pane = onFxThread(() -> built(folder(Root.REVIEW, "Food")));

        assertThat(pane.lookupAll(".review-note-line")).isEmpty();
    }

    @Test
    void pressingTheFoldDrawsEveryLineSluiceWrote() throws Exception {
        final Pipeline pipeline = pipeline(folder(Root.REVIEW, "Food"));
        when(pipeline.reviewNotes(any())).thenReturn(List.of("a.jpg - a plate of food",
                "b.jpg - restaurant table"));
        final Parent pane = onFxThread(() -> built(pipeline));

        onFxThread(() -> press(pane, "#review-notes-review-food"));

        assertThat(pane.lookupAll(".review-note-line")).extracting(node -> ((TextArea) node).getText())
                .containsExactly("a.jpg - a plate of food", "b.jpg - restaurant table");
    }

    @Test
    void nothingWaitingIsSaidOnTheScreenRatherThanLeavingItBlank() throws Exception {
        final Parent pane = onFxThread(ReviewPaneTest::built);

        assertThat(((TextArea) pane.lookup("#review-nothing-yet")).getText())
                .startsWith("Nothing is waiting for you.");
        assertThat(pane.lookupAll(".card")).isEmpty();
    }

    private static List<String> headings(final Parent pane, final String styleClass) {
        return pane.lookupAll(styleClass).stream().map(node -> ((TextField) node).getText()).toList();
    }

    private static boolean press(final Parent pane, final String id) {
        ((Button) Objects.requireNonNull(pane.lookup(id), id + " is not on this screen)")).fire();
        return true;
    }

    private static Folder folder(final Root root, final String name) {
        return folder(root, FiledBy.A_SIFT, name);
    }

    private static Folder heldBack(final String name) {
        return folder(Root.REVIEW, FiledBy.A_SORT, name);
    }

    private static Folder folder(final Root root, final FiledBy filedBy, final String name) {
        return new Folder(root, filedBy, name, WORKING_ROOT.resolve(root.name()).resolve(name),
                4, 0, Instant.now());
    }

    private static Pipeline pipeline(final Folder... folders) {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.reviewListing()).thenReturn(new ReviewListing(List.of(folders), List.of()));
        when(pipeline.reviewNotes(any())).thenReturn(List.of());
        return pipeline;
    }

    private static Parent built(final Folder... folders) {
        return built(pipeline(folders));
    }

    // Read before the pane is built, as well as by the pane's own background read. So no assertion
    // here depends on which of the two lands first.
    private static Parent built(final Pipeline pipeline) {
        final var presenter = new ReviewPresenter(pipeline,
                new RunLauncherPresenter(pipeline, new FxProgressPort()));
        presenter.refresh();
        final var page = (Parent) ReviewPane.pane(presenter);
        final var scene = new Scene(new StackPane(page), 900, 700);
        scene.getStylesheets().add(
                Objects.requireNonNull(ReviewPaneTest.class.getResource("/ui/sluice.css"),
                        "the app stylesheet is missing from the test classpath").toExternalForm());
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return page;
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        final T result = WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
        return result;
    }
}
