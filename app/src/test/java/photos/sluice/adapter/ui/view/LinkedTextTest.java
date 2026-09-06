package photos.sluice.adapter.ui.view;

import javafx.scene.control.Hyperlink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
    void aSentenceWithNoAddressInItOffersNothingToOpen() {
        assertThat(LinkedText.addressIn("Ask whoever runs it for a key.")).isNull();
    }

    @Test
    void anAddressIsFoundWhereverItSitsInTheSentence() {
        assertThat(LinkedText.addressIn("Keys live at https://console.example.test today"))
                .isEqualTo("https://console.example.test");
    }

    @Test
    void punctuationClosingTheSentenceStaysOutOfTheAddress() {
        assertThat(LinkedText.addressIn("Keys live at https://console.example.test."))
                .isEqualTo("https://console.example.test");
    }

    @Test
    void anAddressOfferedOverPlainHttpIsNotOffered() {
        assertThat(LinkedText.addressIn("Try http://localhost:8080 first")).isNull();
    }

    @Test
    void aSchemeInTheMiddleOfAWordIsNotAnAddress() {
        assertThat(LinkedText.addressIn("mailto:someone@https://nope.test")).isNull();
    }

    @Test
    void theFirstAddressWinsWhereASentenceOffersTwo() {
        assertThat(LinkedText.addressIn("Either https://one.example.test or https://two.example.test"))
                .isEqualTo("https://one.example.test");
    }

    // The address stays in the sentence beside it, so the control says what pressing does instead of
    // putting the same address on the screen twice.
    @Test
    void theControlDoesNotRepeatTheAddress() throws Exception {
        final Hyperlink link = onFxThread(() -> LinkedText.opening("https://console.example.test"));

        assertThat(link.getText()).doesNotContain("console.example.test").isNotBlank();
    }

    @Test
    void pressingItHandsTheAddressToTheBrowser() throws Exception {
        final List<String> opened = new ArrayList<>();
        ExternalBrowser.openWith(opened::add);
        final Hyperlink link = onFxThread(() -> LinkedText.opening("https://console.example.test"));

        onFxThread(link::fire);

        assertThat(opened).containsExactly("https://console.example.test");
    }

    // Nothing set to open an address is every render and every test that did not ask for one.
    @Test
    void pressingItWithNoBrowserSetDoesNothing() throws Exception {
        final Hyperlink link = onFxThread(() -> LinkedText.opening("https://console.example.test"));

        onFxThread(link::fire);

        assertThat(link.getText()).isNotBlank();
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    private static void onFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
