package photos.sluice.adapter.ui.view;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The VISION PROVIDER card: the provider dropdown, the model picker, the endpoint row, the
 * watch-mode row, and the credential card nested inside it.
 */
final class VisionProviderCard {

    // An eye, and the stroke that crosses it out. SVG path data, the same format BrandMark uses and
    // the one SVGPath takes: letters are drawing commands, numbers their coordinates. Drawn rather
    // than written, because every password field a user has met uses this and none spell it out.
    //
    // The stroke is a shape rather than a line, and its own node rather than more of the eye's
    // path. An SVGPath is filled, so a line contributes nothing at all. A shape appended to the
    // eye's path is cancelled where the two overlap, by the non-zero winding rule. Measured: as one
    // path the crossed-out eye is pixel-identical to the open one.
    private static final String EYE = "M12 5C5 5 2 12 2 12s3 7 10 7 10-7 10-7-3-7-10-7zm0 12a5 5 0 "
            + "110-10 5 5 0 010 10zm0-8a3 3 0 100 6 3 3 0 000-6z";
    // Its four corners average to the eye's own centre, which is what puts the stroke through the
    // pupil rather than across a corner.
    private static final String CROSSED_OUT = "M2.6 4.4l1.8-1.8 17 17-1.8 1.8z";

    private VisionProviderCard() {}

    /**
     * The built card, and the four nodes a caller has to reach again once a provider changes.
     *
     * @param card {@link VBox} the card itself, for the page to lay out
     * @param providerBox {@link ComboBox} of {@link SettingsView.ProviderChoice} the provider dropdown
     * @param providerFields {@link VBox} the model and endpoint rows
     * @param watchRow {@link VBox} the watch-mode row
     * @param secretCard {@link VBox} the credential card nested inside
     */
    record Result(VBox card, ComboBox<SettingsView.ProviderChoice> providerBox, VBox providerFields,
                  VBox watchRow, VBox secretCard) {
    }

    static Result build(final SettingsView view, final SettingsPresenter presenter) {
        final var providerBox = providerChoice(view);
        providerBox.setId("settings-provider");
        final var providerFields = providerFields(view, presenter, providerBox);
        providerFields.setId("settings-provider-fields");
        final var watchRow = watchModeRow(view);
        watchRow.setId("settings-watch-mode");
        final var secretCard = new VBox();
        secretCard.setId("settings-api-key");
        secretCard.getStyleClass().add("settings-subsection");
        fillSecretCard(secretCard, presenter, providerBox, providerFields, null, view.keyLimit());
        final var card = SettingsRows.card("VISION PROVIDER",
                "What actually looks at your photos and decides what is junk, a duplicate, or worth "
                        + "keeping. Sluice has no judgement of its own. It either calls a model you pay for, "
                        + "or waits for an agent you already run to do the looking. That agent writes its "
                        + "answers into a folder, so it has to be one that can work with files rather than "
                        + "only chat.",
                providerRow(providerBox, view.providerOverride(), view.providerUnrecognised()),
                providerFields, watchRow, secretCard);

        // Every provider shares one set of controls, so what the chosen one does not use is hidden
        // rather than rebuilt. A rebuild reads the saved settings back, wiping anything typed but
        // not yet saved. That is every field on the page, not only this card's.
        showOnlyWhatTheProviderUses(providerChoiceOf(providerBox).fields(), providerFields, watchRow, secretCard);

        return new Result(card, providerBox, providerFields, watchRow, secretCard);
    }

    static SettingsView.ProviderChoice providerChoiceOf(final ComboBox<SettingsView.ProviderChoice> box) {
        return box.getSelectionModel().getSelectedItem();
    }

    /**
     * Hides every control the chosen provider has no use for.
     *
     * <p>Which those are is the chosen {@link SettingsView.ProviderChoice}'s own answer; this only
     * applies it. A hidden control is unmanaged too, or the layout keeps its gap and the card reads
     * as though something failed to draw.
     *
     * @param fields {@link SettingsView.ProviderFields} which settings the chosen provider uses
     * @param providerFields {@link VBox} the model and endpoint rows
     * @param watchRow {@link VBox} the watch-mode row
     * @param secretCard {@link VBox} the credential card
     */
    static void showOnlyWhatTheProviderUses(final SettingsView.ProviderFields fields,
                                            final VBox providerFields, final VBox watchRow,
                                            final VBox secretCard) {
        final ProviderFieldControls controls = controlsOf(providerFields);
        showIf(fields.model(), controls.model().getParent());
        showIf(fields.endpoint(), controls.endpointField().getParent());
        showIf(fields.watchMode(), watchRow);
        showIf(fields.credential(), secretCard);
        showIf(fields.model() || fields.endpoint(), providerFields);
    }

    /**
     * The controls inside the provider block, for whoever has to reach one of them again.
     *
     * <p>{@code endpointField} is the node the endpoint row was built around and {@code endpoint} is
     * the field inside it. Both are here because they answer different questions. The row is hidden
     * and shown by its parent; the field is what carries the text.
     *
     * @param model {@link ComboBox} of {@link SettingsView.ModelChoice} the model picker
     * @param modelInfo {@link VBox} the note drawn under the picker
     * @param endpoint {@link TextField} the endpoint value
     * @param endpointField {@link HBox} the row the endpoint sits in, hidden and shown as a whole
     * @param testConnection {@link Button} runs a live check against the provider
     * @param testResult {@link Label} what that check answered
     * @param modelViolation {@link Label} what a refused save says about the model
     */
    record ProviderFieldControls(ComboBox<SettingsView.ModelChoice> model, VBox modelInfo,
                                 TextField endpoint, HBox endpointField, Button testConnection,
                                 Label testResult, Label modelViolation) {
    }

    /**
     * How one model reads in the picker's dropdown, with the recommended one saying so.
     *
     * <p>A named class rather than an anonymous one, and package-visible rather than private.
     * {@code ScreenWarmUp} can then construct the exact type this file builds, instead of an
     * anonymous class only this one method could ever build again.
     */
    static final class ModelChoiceCell extends ListCell<SettingsView.ModelChoice> {
        @Override
        protected void updateItem(final SettingsView.ModelChoice item, final boolean empty) {
            super.updateItem(item, empty);
            // ListCell's own type carries no nullness annotation, so the IDE reads item as never
            // null here. Cell.updateItem's own Javadoc gives "empty || item == null" as the correct
            // guard, matched exactly.
            //noinspection ConstantValue
            this.setText(empty || item == null ? null
                    : item.recommended() ? item.label() + " (recommended)" : item.label());
        }
    }

    /**
     * Reads what the presenter currently has to say about one provider's models, cached rather than
     * freshly checked, and draws it.
     *
     * <p>Called both when the model row is first built and whenever the provider dropdown changes.
     * Never checks the service on its own: a dropdown a user is only browsing must not spend a
     * network call every time it changes.
     *
     * @param model {@link ComboBox} the model picker
     * @param modelInfo {@link VBox} where the source note, a violation, or a caution lands
     * @param presenter {@link SettingsPresenter} answers what to draw
     * @param providerId {@link String} the provider to draw a picker for
     * @param providerBox {@link ComboBox} of {@link SettingsView.ProviderChoice} the chosen provider,
     *         read again once a check completes in case the choice moved on while it ran
     */
    static void selectModelPickerFor(final ComboBox<SettingsView.ModelChoice> model, final VBox modelInfo,
                                     final SettingsPresenter presenter, final String providerId,
                                     final ComboBox<SettingsView.ProviderChoice> providerBox) {
        final SettingsPresenter.ModelPickerResult result = presenter.modelPickerFor(providerId);
        showModelPicker(model, modelInfo, result, presenter, providerId, providerBox);
    }

    static ProviderFieldControls controlsOf(final VBox providerFields) {
        return (ProviderFieldControls) providerFields.getProperties().get("controls");
    }

    /**
     * The id of whichever model is selected, or empty when nothing is. Either the picker is
     * disabled, or a provider with no model setting never showed one at all.
     *
     * @param model {@link ComboBox} the model picker
     * @return {@link String} the selected model's id, or empty
     */
    static String selectedModelId(final ComboBox<SettingsView.ModelChoice> model) {
        final SettingsView.ModelChoice selected = model.getSelectionModel().getSelectedItem();
        // The property's declared type is not nullable, so the IDE reads this guard as always
        // false. A disabled Unavailable picker, or a provider with no model setting, leaves this
        // genuinely unselected.
        //noinspection ConstantValue
        return selected == null ? "" : selected.id();
    }

    static boolean watchAutomaticallyOf(final VBox watchRow) {
        final var watchToggle = (RadioButton) watchRow.getProperties().get("watchToggle");
        return watchToggle.isSelected();
    }

    /**
     * Fills the credential block with what the chosen provider's credential looks like right now.
     *
     * <p>A key is stored the moment its own button is pressed, so only this block has anything to
     * redraw afterwards. Redrawing the page instead would read every other field back from what is
     * saved, throwing away a provider picked but not yet saved along with anything typed.
     *
     * <p>Which provider it is comes from the dropdown rather than from a caller. A stored key then
     * cannot land under a provider other than the one on screen.
     *
     * @param card {@link VBox} the block to fill, whatever it held before
     * @param presenter {@link SettingsPresenter} reads and writes that credential
     * @param providerBox {@link ComboBox} of {@link SettingsView.ProviderChoice} the chosen provider
     * @param providerFields {@link VBox} the model and endpoint rows, whose Test connection button a
     *         credential change leaves enabled or not, and whose model picker a save or remove can
     *         change the very choices in
     * @param said what just happened to the key, or null when nothing has
     * @param keyLimit int the most the entry field may hold
     */
    static void fillSecretCard(final VBox card, final SettingsPresenter presenter,
                               final ComboBox<SettingsView.ProviderChoice> providerBox,
                               final VBox providerFields, final @Nullable String said,
                               final int keyLimit) {
        final String providerId = providerChoiceOf(providerBox).id();
        card.getChildren().setAll(secretCardContents(presenter, presenter.secretRow(providerId), providerId,
                said, message -> fillSecretCard(card, presenter, providerBox, providerFields, message, keyLimit),
                providerFields, providerBox, keyLimit));
        // The button that was pressed leaves the scene along with the rest of this block. Focus goes
        // to whatever the window finds next, and a scrolling pane travels to wherever focus lands.
        // A key saved half way down the page then shows the top of it. Putting focus back on the
        // button that replaced it keeps the reader where they were standing.
        final Node pressedAgain = card.lookup("#settings-api-key-save");
        if (said != null && pressedAgain != null) {
            pressedAgain.requestFocus();
        }
        // A save or a remove is exactly what Test's own enabled state depends on.
        final ProviderFieldControls controls = controlsOf(providerFields);
        enableTestIfThereIsSomethingToTry(controls.testConnection(), presenter, providerBox);
    }

    private static List<Node> secretCardContents(final SettingsPresenter presenter,
                                                 final SettingsView.SecretRow secret,
                                                 final String providerId, final @Nullable String said,
                                                 final Consumer<String> onChanged,
                                                 final VBox providerFields,
                                                 final ComboBox<SettingsView.ProviderChoice> providerBox,
                                                 final int keyLimit) {
        final var entry = new PasswordField();
        entry.setId("settings-api-key-entry");
        entry.setPromptText("Paste a new key to save or replace it");
        final var reveal = new TextField();
        reveal.setPromptText(entry.getPromptText());
        // Both halves of the same value, so both take the same ceiling. The store refuses past it
        // too, and this is the half that stops it being typed.
        SettingsRows.holdTo(entry, keyLimit);
        SettingsRows.holdTo(reveal, keyLimit);
        reveal.managedProperty().bind(reveal.visibleProperty());
        entry.managedProperty().bind(entry.visibleProperty());
        reveal.setVisible(false);
        reveal.textProperty().bindBidirectional(entry.textProperty());

        final var eye = revealButton(entry, reveal);

        // Activate rather than Save. The press does more than store the key: it asks the provider
        // what this account can run, and redraws the picker with the answer. Both halves name the
        // key, so the control reads as one thing in either state. That also keeps either from being
        // mistaken for the page's own Save, pinned in the bar above.
        final var saveButton = new Button(secret.hasStoredValue() ? "Replace key" : "Activate key");
        saveButton.setId("settings-api-key-save");
        final var result = new Label();
        result.setWrapText(true);
        final ProviderFieldControls controls = controlsOf(providerFields);
        saveButton.setOnAction(_ -> {
            final String error = presenter.saveSecret(providerId, entry.getText());
            if (error != null) {
                result.setText(error);
                result.getStyleClass().setAll("settings-violation");
            } else {
                onChanged.accept("API key saved.");
                refreshModelPicker(controls.model(), controls.modelInfo(), presenter, providerId, providerBox);
            }
        });

        final var removeButton = new Button("Remove");
        removeButton.setId("settings-api-key-remove");
        // Quiet beside Activate key. The fill says which action the card wants pressed, and it is
        // never the one that throws the credential away.
        removeButton.getStyleClass().add("button-quiet");
        removeButton.setDisable(!secret.hasStoredValue());
        removeButton.setOnAction(_ -> {
            // Asked before anything is cleared. A key is the one thing on this card the app cannot
            // put back, since Sluice never reads a stored credential out. An accidental press costs
            // the user a trip to their provider for a new one.
            final SettingsPresenter.SecretRemoval removal = presenter.secretRemoval(providerId);
            // Backing out leads, because this asks about the one thing on the card the app cannot
            // put back.
            if (Dialogs.ask(removal.heading(), removal.question(),
                    new Dialogs.Choice("Remove key", Dialogs.Role.GO_AHEAD, Dialogs.Emphasis.QUIET),
                    new Dialogs.Choice("Keep it", Dialogs.Role.CANCEL, Dialogs.Emphasis.LOUD)).isEmpty()) {
                return;
            }
            final String error = presenter.removeSecret(providerId);
            if (error != null) {
                result.setText(error);
                result.getStyleClass().setAll("settings-violation");
            } else {
                onChanged.accept(removal.removed());
                refreshModelPicker(controls.model(), controls.modelInfo(), presenter, providerId, providerBox);
            }
        });

        // Beside the row it happened on rather than at the top of the page, which is not where the
        // reader is standing. Taken away again on its own, the way the page's own banner is.
        if (said != null) {
            result.setText(said);
            result.getStyleClass().setAll("settings-confirmation");
            takeAwayAfterFourSeconds(result);
        }

        final var entryRow = new HBox(entry, reveal, eye, saveButton, removeButton);
        entryRow.getStyleClass().add("settings-field-row");
        // Whichever of the two entries is showing takes the width the buttons leave. Both are told,
        // since which one is visible flips every time Show is pressed.
        HBox.setHgrow(entry, Priority.ALWAYS);
        HBox.setHgrow(reveal, Priority.ALWAYS);

        final var reassurance = new Label(secret.reassurance());
        reassurance.setWrapText(true);
        reassurance.getStyleClass().add("settings-reassurance");

        final String setupGuide = providerChoiceOf(providerBox).setupGuide();

        // Said here, at the moment someone opts into paying, rather than buried in a licence file.
        // This card only ever shows for a provider that calls a model with this key, so the spend
        // is real every time it does. Its own bordered ground, not just a line of text, since a
        // spend is worth noticing rather than reading past.
        final var billingText = new Label("Every run spends against your own account with this provider. "
                + "Checking your key or its models costs nothing.");
        billingText.setWrapText(true);
        billingText.getStyleClass().add("settings-caution");
        final Node billing = SettingsRows.badgedCallout(new VBox(billingText));

        final var children = new ArrayList<Node>(List.of(SettingsRows.subsectionHeading("API key")));
        // Above the entry rather than in place of it. A store that will not say what it holds can
        // still be written to. Taking the field away leaves a user who cannot read their key with
        // no way to set another one.
        if (secret.errorMessage() != null) {
            final var error = new Label(secret.errorMessage());
            error.setWrapText(true);
            error.getStyleClass().add("settings-violation-detail");
            children.add(error);
        }
        children.add(entryRow);
        // Under the field it fills, and above where the key ends up. That is the order somebody
        // with no key yet needs them in: they cannot act on where a key is stored until they have
        // one.
        if (setupGuide != null) {
            final var whereToGetOne = LinkedText.of(setupGuide);
            whereToGetOne.setId("settings-api-key-setup-guide");
            whereToGetOne.getStyleClass().add("settings-help");
            children.add(whereToGetOne);
        }
        children.addAll(List.of(reassurance, billing));
        if (secret.environmentOverride() != null) {
            children.add(SettingsRows.overrideLabel(secret.environmentOverride()));
        }
        if (secret.multiHolder() != null) {
            children.add(SettingsRows.overrideLabel(secret.multiHolder()));
        }
        children.add(result);
        return children;
    }

    /**
     * Fades a line out and empties it, four seconds after it appeared.
     *
     * <p>Emptied rather than left invisible, since the label stays in the card and is written to
     * again the next time something happens to the key. Its opacity is put back for the same
     * reason.
     *
     * @param line {@link Label} the line to take away
     */
    private static void takeAwayAfterFourSeconds(final Label line) {
        final var fade = new FadeTransition(Duration.millis(400), line);
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setOnFinished(_ -> {
            line.setText("");
            line.setOpacity(1);
        });
        final var wait = new PauseTransition(Duration.seconds(4));
        wait.setOnFinished(_ -> fade.play());
        wait.play();
    }

    /**
     * The dropdown of every provider a user can pick, opened on the configured one.
     *
     * <p>Shared with the first-run card, which offers the same choice before this card exists.
     *
     * @param view {@link SettingsView} carries the providers and which is selected
     * @return {@link ComboBox} of {@link SettingsView.ProviderChoice} the dropdown
     */
    static ComboBox<SettingsView.ProviderChoice> providerChoice(final SettingsView view) {
        final var box = new ComboBox<SettingsView.ProviderChoice>();
        box.getItems().setAll(view.providers());
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(final SettingsView.@Nullable ProviderChoice choice) {
                return choice == null ? "" : choice.label();
            }

            @Override
            public SettingsView.ProviderChoice fromString(final String label) {
                throw new UnsupportedOperationException("this converter is display-only");
            }
        });
        // The presenter guarantees view.provider() names one of view.providers(); it resolves an
        // unrecognized configured id to a known one before this ever runs.
        box.getSelectionModel().select(view.providers().stream()
                .filter(choice -> choice.id().equals(view.provider())).findFirst().orElseThrow());
        return box;
    }

    /**
     * The provider dropdown, with whatever outranks it noted underneath.
     *
     * <p>No label of its own, where every other control has one. The panel is named for this
     * setting, so a second name above the control would read as a different one.
     *
     * @param box {@link ComboBox} of {@link SettingsView.ProviderChoice} the dropdown
     * @param overrideNote a note about what outranks this value, or null
     * @param unrecognisedNote a note that the configured provider is not one this install has, or
     *     null
     * @return {@link VBox} the row
     */
    private static VBox providerRow(final ComboBox<SettingsView.ProviderChoice> box,
                                    final @Nullable String overrideNote,
                                    final @Nullable String unrecognisedNote) {
        final var row = new VBox(box);
        // Above the precedence note. The two can both apply, and this one is the reason the
        // dropdown reads as it does.
        if (unrecognisedNote != null) {
            row.getChildren().add(SettingsRows.cautionRow(unrecognisedNote));
        }
        if (overrideNote != null) {
            row.getChildren().add(SettingsRows.overrideLabel(overrideNote));
        }
        row.getStyleClass().add("settings-row");
        return row;
    }

    private static void showIf(final boolean wanted, final Node node) {
        node.setVisible(wanted);
        node.setManaged(wanted);
    }

    private static VBox providerFields(final SettingsView view, final SettingsPresenter presenter,
                                       final ComboBox<SettingsView.ProviderChoice> providerBox) {
        final var model = new ComboBox<SettingsView.ModelChoice>();
        model.setId("settings-model");
        model.getStyleClass().add("settings-model-picker");
        model.setCellFactory(_ -> new ModelChoiceCell());
        model.setButtonCell(new ModelChoiceCell());
        final var modelInfo = new VBox();
        modelInfo.getStyleClass().add("settings-model-info");
        selectModelPickerFor(model, modelInfo, presenter, providerChoiceOf(providerBox).id(), providerBox);

        final var endpoint = new TextField(view.endpoint() == null ? "" : view.endpoint());
        endpoint.setId("settings-endpoint");
        SettingsRows.holdTo(endpoint, view.endpointLimit());
        // Shown as a prompt, and deliberately not offered the way a folder row offers its suggestion.
        // This one discloses what an empty field already reaches rather than proposing a value to
        // store. Typing it in would pin the endpoint to today's address, so a reader who accepted it
        // would stop following the provider the day it moved.
        endpoint.setPromptText(providerChoiceOf(providerBox).defaultEndpoint());
        // Names what it tries rather than sitting as a bare verb beside Activate key. The two are a
        // press apart and would otherwise read as degrees of the same thing.
        final var testConnection = new Button("Test connection");
        testConnection.setId("settings-test-connection");
        testConnection.getStyleClass().add("button-quiet");
        final var testResult = new Label();
        testResult.setId("settings-test-result");
        testResult.setWrapText(true);
        testResult.getStyleClass().add("settings-help");
        // Nothing has been tested until the button is pressed, and a label holding no text still
        // takes a line's height. Left alone it puts an empty gap below the Endpoint row.
        testResult.managedProperty().bind(testResult.visibleProperty());
        testResult.visibleProperty().bind(testResult.textProperty().isNotEmpty());
        enableTestIfThereIsSomethingToTry(testConnection, presenter, providerBox);
        testConnection.setOnAction(_ -> {
            testResult.setText("Checking...");
            testResult.getStyleClass().setAll("settings-help");
            final String providerId = providerChoiceOf(providerBox).id();
            final String typed = endpoint.getText();
            final var task = new Task<SettingsPresenter.ConnectionCheckResult>() {
                @Override
                protected SettingsPresenter.ConnectionCheckResult call() {
                    return presenter.testConnection(providerId, typed);
                }
            };
            // Guarded on the provider. The dropdown may have moved on while this ran, and its
            // answer belongs to the one it was asked about.
            task.setOnSucceeded(_ -> {
                if (providerChoiceOf(providerBox).id().equals(providerId)) {
                    final SettingsPresenter.ConnectionCheckResult result = task.getValue();
                    testResult.setText(result.message());
                    testResult.getStyleClass().setAll(result.succeeded() ? "settings-help" : "settings-violation");
                }
            });
            task.setOnFailed(_ -> {
                if (providerChoiceOf(providerBox).id().equals(providerId)) {
                    testResult.setText("Sluice could not check this connection.");
                    testResult.getStyleClass().setAll("settings-violation");
                }
            });
            Thread.ofVirtual().start(task);
        });

        // Built whether or not there is anything to say, so a refused save can fill it without
        // rebuilding the row. It takes no space while empty.
        final var modelViolation = SettingsRows.violationLabel();
        SettingsRows.markWhileSomethingIsWrong(model, modelViolation);

        final var modelRow = SettingsRows.explainedRow("Model",
                "Which model reads your photos.",
                model, view.modelOverride());
        modelRow.getChildren().addAll(modelInfo, modelViolation);

        // Beside the field rather than under it, the way Browse sits beside a folder root and the
        // key buttons beside the key entry. A button under its own field reads as belonging to
        // whatever comes next. What the press answers goes underneath, where every other row puts
        // what it has to say.
        final var endpointField = new HBox(endpoint, testConnection);
        endpointField.getStyleClass().add("settings-field-row");
        HBox.setHgrow(endpoint, Priority.ALWAYS);
        final var endpointRow = SettingsRows.explainedRow("Endpoint",
                "Leave empty unless you are pointing Sluice at something other than the provider's own "
                        + "service, such as a proxy on your network.", endpointField, view.endpointOverride());
        endpointRow.getChildren().add(testResult);

        final var box = new VBox(modelRow, endpointRow);
        box.getStyleClass().add("settings-group");
        box.getProperties().put("controls",
                new ProviderFieldControls(model, modelInfo, endpoint, endpointField, testConnection,
                        testResult, modelViolation));
        return box;
    }

    /**
     * Leaves Test pressable only where there is something to try: a credential for whichever
     * provider is chosen. An empty endpoint still tries the provider's own default service, which
     * is what the help text under the field tells a reader to leave it as.
     *
     * <p>The provider is read from the dropdown each time rather than captured. One row serves every
     * provider. So the credential this asks about is a fact about the current choice, never about
     * the one on screen when the row was first drawn.
     *
     * @param testConnection {@link Button} the Test connection button
     * @param presenter {@link SettingsPresenter} answers whether a credential is stored
     * @param providerBox {@link ComboBox} of {@link SettingsView.ProviderChoice} the chosen provider
     */
    private static void enableTestIfThereIsSomethingToTry(final Button testConnection,
                                                          final SettingsPresenter presenter,
                                                          final ComboBox<SettingsView.ProviderChoice> providerBox) {
        testConnection.setDisable(!presenter.secretRow(providerChoiceOf(providerBox).id()).hasStoredValue());
    }

    /**
     * Draws one already-resolved {@link SettingsPresenter.ModelPickerResult}: a list to choose from,
     * or nothing to choose from and a Retry that checks again.
     *
     * @param model {@link ComboBox} the model picker
     * @param modelInfo {@link VBox} where the source note, a violation, or a caution lands
     * @param result {@link SettingsPresenter.ModelPickerResult} what to draw
     * @param presenter {@link SettingsPresenter} Retry checks through this
     * @param providerId {@link String} the provider this picker belongs to
     * @param providerBox {@link ComboBox} of {@link SettingsView.ProviderChoice} the chosen provider
     */
    private static void showModelPicker(final ComboBox<SettingsView.ModelChoice> model, final VBox modelInfo,
                                        final SettingsPresenter.ModelPickerResult result,
                                        final SettingsPresenter presenter, final String providerId,
                                        final ComboBox<SettingsView.ProviderChoice> providerBox) {
        modelInfo.getChildren().clear();
        switch (result.picker()) {
            // Reached only for a provider this row's own showOnlyWhatTheProviderUses call already
            // hides. Cleared rather than left showing whatever the last provider offered.
            case null -> emptyAndDisabled(model);
            case final SettingsView.ModelPicker.Options options -> {
                model.setDisable(false);
                model.getItems().setAll(options.choices());
                // SettingsPresenter.picked() only ever answers a selected() among choices(); a miss
                // here is that promise broken rather than a state this screen has to tolerate.
                model.getSelectionModel().select(options.choices().stream()
                        .filter(choice -> choice.id().equals(options.selected()))
                        .findFirst()
                        .orElseThrow());
                modelInfo.getChildren().add(SettingsRows.helpLine(options.sourceNote()));
            }
            case SettingsView.ModelPicker.Pending(final String message) -> {
                emptyAndDisabled(model);
                model.setPromptText(message);
                // Holds one line's height, so the rows below do not jump when the answer arrives
                // and fills this slot. An empty instance of the same styled line rather than a
                // number, which would be a second place to keep the font size in step. One line is
                // what the answer usually brings; a wrapped violation is taller and still moves.
                modelInfo.getChildren().add(SettingsRows.helpLine(""));
                redrawWhenTheStartUpCheckSettles(model, modelInfo, presenter, providerId, providerBox);
            }
            case final SettingsView.ModelPicker.Unavailable unavailable -> {
                emptyAndDisabled(model);
                // A closed ComboBox with nothing selected draws its own promptText. It never asks
                // a custom cell factory to draw the empty case. Confirmed by rendering: the cell's
                // own text for a null item never appeared on screen.
                model.setPromptText("Nothing to choose from");
                final var violation = new Label(unavailable.violation());
                violation.setWrapText(true);
                violation.getStyleClass().add("settings-violation");
                final var retry = new Button("Retry");
                retry.setId("settings-model-retry");
                retry.getStyleClass().add("button-quiet");
                // The block it sits in has to fill the row, because the message above it wraps. A
                // button left to fill with it would run the width of the card.
                retry.setMaxWidth(Region.USE_PREF_SIZE);
                // Answered on the press rather than only when the answer lands. A provider that has
                // stopped responding is exactly the one a reader presses this against, and it takes
                // the interactive timeout to say so. Left as it was, the press reads as ignored.
                //
                // Worded here, unlike the picker's own waiting text, which the presenter supplies.
                // Every button on this screen names itself; every line about the models comes from
                // the presenter. This is a button saying what it is doing.
                retry.setOnAction(_ -> {
                    retry.setDisable(true);
                    retry.setText("Connecting...");
                    refreshModelPicker(model, modelInfo, presenter, providerId, providerBox);
                });
                modelInfo.getChildren().addAll(violation, retry);
            }
        }
        if (result.unrecognisedNote() != null) {
            modelInfo.getChildren().add(SettingsRows.cautionRow(result.unrecognisedNote()));
        }
    }

    /**
     * Leaves the picker with nothing in it and nothing selected, for every state that offers no
     * choice. Whatever the last provider offered would otherwise still be sitting there.
     *
     * @param model {@link ComboBox} the model picker
     */
    private static void emptyAndDisabled(final ComboBox<SettingsView.ModelChoice> model) {
        model.setDisable(true);
        model.getItems().clear();
        model.getSelectionModel().clearSelection();
        // Whichever state follows says what it wants said. A prompt left standing would be the
        // previous state still talking.
        model.setPromptText(null);
    }

    /**
     * Draws the picker again once the check made as the app opened has settled.
     *
     * <p>This screen reads the picker when it is built, and the three things that redraw it are all
     * things a user does. The check answering is not one of them. Without this, a screen opened
     * during it would say it was checking for as long as it stayed open.
     *
     * @param model {@link ComboBox} the model picker
     * @param modelInfo {@link VBox} where the source note, a violation, or a caution lands
     * @param presenter {@link SettingsPresenter} holds the wait and the answer
     * @param providerId {@link String} the provider being waited on
     * @param providerBox {@link ComboBox} of {@link SettingsView.ProviderChoice} the chosen provider,
     *         read again once the wait ends in case the choice moved on while it ran
     */
    private static void redrawWhenTheStartUpCheckSettles(final ComboBox<SettingsView.ModelChoice> model,
                                                         final VBox modelInfo,
                                                         final SettingsPresenter presenter,
                                                         final String providerId,
                                                         final ComboBox<SettingsView.ProviderChoice> providerBox) {
        final var task = new Task<Void>() {
            @Override
            protected Void call() {
                presenter.awaitStartUpCheck(providerId);
                return null;
            }
        };
        redrawWhicheverWayItEnds(task, model, modelInfo, presenter, providerId, providerBox);
    }

    /**
     * Checks a provider's stored credential against the real service, off the FX thread, and redraws
     * the picker with what came back.
     *
     * <p>Two callers reach this: Retry, and a credential save that just changed what this account
     * can run. Both want the same thing - forget the last answer, ask again, draw what comes back.
     *
     * @param model {@link ComboBox} the model picker
     * @param modelInfo {@link VBox} where the source note, a violation, or a caution lands
     * @param presenter {@link SettingsPresenter} runs the check and keeps its answer
     * @param providerId {@link String} the provider to check
     * @param providerBox {@link ComboBox} of {@link SettingsView.ProviderChoice} the chosen provider
     */
    private static void refreshModelPicker(final ComboBox<SettingsView.ModelChoice> model, final VBox modelInfo,
                                           final SettingsPresenter presenter, final String providerId,
                                           final ComboBox<SettingsView.ProviderChoice> providerBox) {
        final var task = new Task<Void>() {
            @Override
            protected Void call() {
                presenter.refreshModels(providerId);
                return null;
            }
        };
        redrawWhicheverWayItEnds(task, model, modelInfo, presenter, providerId, providerBox);
    }

    /**
     * Runs a picker task and redraws the row when it ends, however it ends.
     *
     * <p>On failure as well as success, because the row it leaves behind is the reader's only way
     * back. Retry disables itself on the press, so a task that ends without a redraw leaves that
     * button dead for the life of the screen.
     *
     * <p>Guarded on the provider. The dropdown may have moved on while this ran, and the answer
     * belongs to the one it was asked about.
     *
     * @param task {@link Task} the check to run
     * @param model {@link ComboBox} the model picker
     * @param modelInfo {@link VBox} where the source note, a violation, or a caution lands
     * @param presenter {@link SettingsPresenter} answers what to draw
     * @param providerId {@link String} the provider this task asked about
     * @param providerBox {@link ComboBox} of {@link SettingsView.ProviderChoice} the chosen provider
     */
    private static void redrawWhicheverWayItEnds(final Task<Void> task,
                                                 final ComboBox<SettingsView.ModelChoice> model,
                                                 final VBox modelInfo,
                                                 final SettingsPresenter presenter,
                                                 final String providerId,
                                                 final ComboBox<SettingsView.ProviderChoice> providerBox) {
        final Runnable redraw = () -> {
            if (providerChoiceOf(providerBox).id().equals(providerId)) {
                selectModelPickerFor(model, modelInfo, presenter, providerId, providerBox);
            }
        };
        task.setOnSucceeded(_ -> redraw.run());
        task.setOnFailed(_ -> redraw.run());
        Thread.ofVirtual().start(task);
    }

    private static VBox watchModeRow(final SettingsView view) {
        final var group = new ToggleGroup();
        final var manual = new RadioButton("Wait for me before moving any photos");
        manual.setToggleGroup(group);
        final var watch = new RadioButton("Move the photos without asking me");
        watch.setToggleGroup(group);
        (view.watchAutomatically() ? watch : manual).setSelected(true);
        // The choices sit in their own box, so a note added below lands under the pair rather than
        // against the last option. At one spacing they read as a remark about that option alone.
        final var choices = new VBox(watch, manual);
        choices.getStyleClass().add("settings-choices");
        final var help = new Label("Sifting only writes decisions down. Moving your photos happens "
                + "afterwards, and this is whether Sluice waits for you before it starts.");
        help.setWrapText(true);
        help.getStyleClass().add("settings-help");
        final var box = new VBox(SettingsRows.fieldLabel("When an external agent has finished sifting"), help,
                choices);
        box.getStyleClass().add("settings-row");
        if (view.watchModeOverride() != null) {
            box.getChildren().add(SettingsRows.overrideLabel(view.watchModeOverride()));
        }
        box.getProperties().put("watchToggle", watch);
        return box;
    }

    /**
     * The control that shows a typed key and hides it again.
     *
     * <p>Carries its meaning as an icon, and its name for anything that cannot see one. A password
     * field's reveal is the one control where the picture is the convention and the word is not.
     *
     * @param hidden {@link PasswordField} the masked entry
     * @param shown {@link TextField} the plain entry bound to the same text
     * @return {@link Button} the reveal toggle
     */
    private static Button revealButton(final PasswordField hidden, final TextField shown) {
        final var eye = new SVGPath();
        eye.setContent(EYE);
        eye.getStyleClass().add("reveal-glyph");

        final var crossedOut = new SVGPath();
        crossedOut.setContent(CROSSED_OUT);
        crossedOut.getStyleClass().add("reveal-glyph");
        crossedOut.setVisible(false);

        final var button = new Button();
        button.setId("settings-key-reveal");
        button.setGraphic(new Group(eye, crossedOut));
        button.getStyleClass().add("button-quiet");
        button.setAccessibleText("Show the key");
        button.setOnAction(_ -> {
            final boolean wasShowing = shown.isVisible();
            shown.setVisible(!wasShowing);
            hidden.setVisible(wasShowing);
            crossedOut.setVisible(!wasShowing);
            button.setAccessibleText(wasShowing ? "Show the key" : "Hide the key");
        });
        return button;
    }
}
