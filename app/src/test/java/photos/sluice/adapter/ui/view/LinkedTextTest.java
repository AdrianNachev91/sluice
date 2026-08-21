package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// What a reader ends up with has to be the sentence they were given, whatever this split it into.
// Several tests check that alongside whatever they are really about. A splitter that drops or
// doubles a character is the failure nobody would catch reading the parts.
class LinkedTextTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    // The opener is process-wide, so a test that sets one would otherwise decide what the next one
    // opens.
    @AfterEach
    void forgetTheBrowser() {
        ExternalBrowser.clear();
    }

    @Test
    void aSentenceWithNoAddressInItIsAllPlainText() throws Exception {
        final TextFlow flow = onFxThread(() -> LinkedText.of("Ask whoever runs it for a key."));

        assertThat(linksIn(flow)).isEmpty();
        assertThat(sentenceOf(flow)).isEqualTo("Ask whoever runs it for a key.");
    }

    @Test
    void anAddressBecomesALinkOnItsOwnWords() throws Exception {
        final TextFlow flow = onFxThread(() -> LinkedText.of("Keys live at https://console.example.test today"));

        assertThat(linksIn(flow)).containsExactly("https://console.example.test");
        assertThat(sentenceOf(flow)).isEqualTo("Keys live at https://console.example.test today");
    }

    @Test
    void punctuationClosingTheSentenceStaysOutOfTheAddress() throws Exception {
        final TextFlow flow = onFxThread(() -> LinkedText.of("Keys live at https://console.example.test."));

        assertThat(linksIn(flow)).containsExactly("https://console.example.test");
        assertThat(sentenceOf(flow)).isEqualTo("Keys live at https://console.example.test.");
    }

    @Test
    void anAddressOfferedOverPlainHttpIsLeftAsWords() throws Exception {
        final TextFlow flow = onFxThread(() -> LinkedText.of("Try http://localhost:8080 first"));

        assertThat(linksIn(flow)).isEmpty();
        assertThat(sentenceOf(flow)).isEqualTo("Try http://localhost:8080 first");
    }

    @Test
    void aSchemeInTheMiddleOfAWordIsNotAnAddress() throws Exception {
        final TextFlow flow = onFxThread(() -> LinkedText.of("mailto:someone@https://nope.test"));

        assertThat(linksIn(flow)).isEmpty();
    }

    @Test
    void pressingALinkHandsTheAddressToTheBrowser() throws Exception {
        final List<String> opened = new ArrayList<>();
        ExternalBrowser.openWith(opened::add);
        final TextFlow flow = onFxThread(() -> LinkedText.of("Keys live at https://console.example.test."));

        pressTheLinkIn(flow);

        assertThat(opened).containsExactly("https://console.example.test");
    }

    // Nothing set to open an address is every render and every test that did not ask for one.
    @Test
    void pressingALinkWithNoBrowserSetDoesNothing() throws Exception {
        final TextFlow flow = onFxThread(() -> LinkedText.of("Keys live at https://console.example.test."));

        pressTheLinkIn(flow);

        assertThat(linksIn(flow)).containsExactly("https://console.example.test");
    }

    // A Text built by hand carries no style class, so a stylesheet rule written against the one
    // JavaFX puts on a Labeled's own Text reaches every label and none of these. Left unreached they
    // draw at the default black, which reads fine on a light ground and vanishes on a dark one.
    @Test
    void theWordsTakeTheirColourFromTheStylesheet() throws Exception {
        final TextFlow flow = onFxThread(() -> LinkedText.of("Keys live at https://console.example.test."));

        onFxThread(() -> {
            final var scene = new Scene(new StackPane(flow));
            Stylesheet.applyTo(scene);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });

        assertThat(wordsIn(flow)).isNotEmpty()
                .allSatisfy(word -> assertThat(word.getFill()).isNotEqualTo(Color.BLACK));
    }

    private static List<Text> wordsIn(final TextFlow flow) {
        return flow.getChildrenUnmodifiable().stream()
                .filter(Text.class::isInstance)
                .map(Text.class::cast)
                .toList();
    }

    private static void pressTheLinkIn(final TextFlow flow) throws Exception {
        onFxThread(() -> ((Hyperlink) linkNodesIn(flow).getFirst()).fire());
    }

    private static List<Node> linkNodesIn(final TextFlow flow) {
        return flow.getChildrenUnmodifiable().stream().filter(Hyperlink.class::isInstance).toList();
    }

    private static List<String> linksIn(final TextFlow flow) {
        return linkNodesIn(flow).stream().map(node -> ((Hyperlink) node).getText()).toList();
    }

    private static String sentenceOf(final TextFlow flow) {
        return flow.getChildrenUnmodifiable().stream()
                .map(part -> part instanceof final Hyperlink link ? link.getText() : ((Text) part).getText())
                .collect(Collectors.joining());
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    private static void onFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
