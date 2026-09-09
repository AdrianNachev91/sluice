package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.StartupFailureCard;
import photos.sluice.adapter.ui.StartupFailureCard.BusyRoot;
import photos.sluice.adapter.ui.StartupFailureCard.Generic;
import photos.sluice.adapter.ui.StartupFailureCard.RejectedInFile;
import photos.sluice.adapter.ui.StartupFailureCard.Unparsable;
import photos.sluice.adapter.ui.StartupFailurePresenter;

/**
 * What the app shows when its own startup failed. It stands in for the real window rather than
 * appearing beside it, since nothing behind it came up.
 *
 * <p>It opens on the product's own mark and name. That is the one thing this window can still say
 * with certainty. It is also the whole of what somebody sees on a first run that fails.
 *
 * <p>Which card to draw is {@link StartupFailurePresenter#card()}'s decision. This class only
 * lays out whatever it is handed.
 */
final class StartupFailureWindow {

    private StartupFailureWindow() {
    }

    /**
     * Builds the failure scene from what the presenter decided to say, and wires whatever card it
     * chose to the actions a user can take from it.
     *
     * @param presenter {@link StartupFailurePresenter} supplies the headline and the card
     * @param actions {@link StartupFailureActions} what a button on the card can ask the app to do
     * @return {@link Scene} the failure scene, styled by the base stylesheet
     */
    static Scene scene(final StartupFailurePresenter presenter, final StartupFailureActions actions) {
        final var root = new VBox(card(presenter, actions));
        root.getStyleClass().addAll("screen", "failure-screen");
        return Stylesheet.applyTo(new Scene(root, Stylesheet.INITIAL_WIDTH, Stylesheet.INITIAL_HEIGHT));
    }

    /**
     * Builds the panel the screen centres: who is speaking, then what happened, then whatever this
     * failure can be answered with.
     *
     * @param presenter {@link StartupFailurePresenter} supplies the headline and the card
     * @param actions {@link StartupFailureActions} what a button on the card can ask the app to do
     * @return {@link VBox} the card
     */
    private static VBox card(final StartupFailurePresenter presenter, final StartupFailureActions actions) {
        final TextField eyebrow = SelectableText.line("STARTUP");
        eyebrow.getStyleClass().add("eyebrow");

        final TextField headline = SelectableText.line(presenter.headline());
        headline.getStyleClass().add("failure-headline");

        final var rule = new Separator();
        rule.getStyleClass().add("card-rule");

        final var body = bodyFor(presenter.card(), actions);
        body.getStyleClass().add("failure-body");

        final var card = new VBox(brand(), rule, eyebrow, headline, body);
        card.getStyleClass().addAll("card", "failure-card");
        card.setAlignment(Pos.CENTER_LEFT);
        return card;
    }

    /**
     * The card-specific content: the detail line, then whatever this failure's own card adds under
     * it.
     *
     * @param failureCard {@link StartupFailureCard} what to draw
     * @param actions {@link StartupFailureActions} what a button on the card can ask the app to do
     * @return {@link VBox} the card body, ready to sit under the headline
     */
    private static VBox bodyFor(final StartupFailureCard failureCard, final StartupFailureActions actions) {
        return switch (failureCard) {
            case final BusyRoot busy -> busyRootBody(busy, actions);
            case final RejectedInFile rejected -> rejectedInFileBody(rejected, actions);
            case final Unparsable unparsable -> unparsableBody(unparsable, actions);
            case final Generic generic -> genericBody(generic);
        };
    }

    private static VBox busyRootBody(final BusyRoot busy, final StartupFailureActions actions) {
        final var detail = detailLabel(busy.detail());

        final var retry = new Button("Try again");
        retry.setId("retry-button");
        retry.setOnAction(_ -> actions.retry().run());

        return new VBox(detail, retry);
    }

    private static VBox rejectedInFileBody(final RejectedInFile rejected, final StartupFailureActions actions) {
        final var detail = detailLabel(rejected.detail());
        final var spot = spotLabel(rejected.file(), rejected.position());

        final var remove = new Button("Remove this setting");
        remove.setId("remove-setting-button");
        remove.setOnAction(_ -> actions.removeSetting().accept(rejected.property()));

        return new VBox(detail, spot, remove, traceRow(rejected.trace()));
    }

    private static VBox unparsableBody(final Unparsable unparsable, final StartupFailureActions actions) {
        final var detail = detailLabel(unparsable.detail());
        final var spot = spotLabel(unparsable.file(), unparsable.position());

        final TextArea problem = SelectableText.prose(unparsable.problem());
        problem.getStyleClass().add("failure-snippet");

        final var setAside = new Button("Start fresh");
        setAside.setId("set-aside-button");
        setAside.setOnAction(_ -> actions.setAside().run());

        return new VBox(detail, spot, problem, setAside, traceRow(unparsable.trace()));
    }

    private static VBox genericBody(final Generic generic) {
        return new VBox(detailLabel(generic.detail()), traceRow(generic.trace()));
    }

    private static TextArea detailLabel(final String text) {
        final TextArea detail = SelectableText.prose(text);
        detail.getStyleClass().add("failure-detail");
        return detail;
    }

    private static TextArea spotLabel(final String file, final @Nullable String position) {
        final var text = position == null ? file : "%s (%s)".formatted(file, position);
        final TextArea spot = SelectableText.prose(text);
        spot.getStyleClass().add("failure-spot");
        return spot;
    }

    /**
     * The stack trace, behind a disclosure the user opens, with a Copy button beside it that works
     * without opening the disclosure first. A user pasting this into a public issue publishes paths
     * from their own machine. So the control names what it reveals rather than hiding behind
     * "details", and Copy is never gated on reading it first.
     *
     * @param trace {@link String} the whole rendered trace
     * @return {@link HBox} the disclosure and the Copy button, side by side
     */
    private static HBox traceRow(final String trace) {
        final var text = new TextArea(trace);
        text.setEditable(false);
        text.setWrapText(false);
        text.getStyleClass().add("trace-text");

        final var disclosure = new TitledPane("Show the error details", text);
        disclosure.setExpanded(false);
        disclosure.setId("trace-disclosure");
        disclosure.getStyleClass().add("trace-disclosure");

        final var copy = new Button("Copy");
        copy.setId("copy-trace-button");
        copy.setOnAction(_ -> copyToClipboard(trace, copy));

        final var row = new HBox(disclosure, copy);
        row.getStyleClass().add("trace-row");
        return row;
    }

    private static void copyToClipboard(final String trace, final Button copy) {
        CopyableTrace.putOnTheClipboard(trace);
        copy.setText("Copied");
    }

    /**
     * Builds the mark and the name.
     *
     * @return {@link HBox} the brand row
     */
    private static HBox brand() {
        final var mark = BrandMark.styled();

        final TextField name = SelectableText.line("Sluice");
        name.getStyleClass().add("brand-name");

        final var brand = new HBox(mark, name);
        brand.getStyleClass().add("brand");
        return brand;
    }
}
