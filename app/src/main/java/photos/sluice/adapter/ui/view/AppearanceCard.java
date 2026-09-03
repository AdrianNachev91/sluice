package photos.sluice.adapter.ui.view;

import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsView;

/**
 * The APPEARANCE card: the look a user asked for, as one radio button per option.
 */
final class AppearanceCard {

    private AppearanceCard() {}

    record Result(VBox card, HBox themeBox) {
    }

    static Result build(final SettingsView view, final SettingsPresenter presenter) {
        final var themeBox = themeChoices(view, presenter);
        themeBox.setId("settings-theme");
        final var card = SettingsRows.card("APPEARANCE", null,
                SettingsRows.inlineLabeledRow("Theme", themeBox, view.themeOverride()));
        return new Result(card, themeBox);
    }

    /**
     * The look a user asked for, as one radio button per option, on a single line.
     *
     * <p>A dropdown hides every option but the chosen one. There are three, they are short, and
     * they are the kind of thing a user changes by comparing rather than by knowing.
     *
     * <p>On one line rather than stacked, each option being one or two words.
     *
     * @param view {@link SettingsView} the state to draw
     * @return {@link HBox} the choices, each button carrying the option it stands for
     */
    private static HBox themeChoices(final SettingsView view, final SettingsPresenter presenter) {
        final var group = new ToggleGroup();
        final var choices = new HBox();
        choices.getStyleClass().add("settings-choices-inline");
        for (final SettingsView.ThemeOption option : view.themes()) {
            final var button = new RadioButton(option.label());
            button.setToggleGroup(group);
            button.setUserData(option.id());
            button.setSelected(option.id().equals(view.theme()));
            choices.getChildren().add(button);
        }
        // After the loop, so the initial selection above does not fire this as though it were a
        // click. A radio saves the instant it is picked, and the screen opening is not a pick.
        group.selectedToggleProperty().addListener((_, _, selected) -> {
            if (selected != null) {
                presenter.chooseTheme((String) selected.getUserData());
            }
        });
        return choices;
    }

    static String themeChoiceOf(final HBox choices) {
        return (String) choices.getChildren().stream()
                .map(RadioButton.class::cast)
                .filter(RadioButton::isSelected)
                .findFirst()
                .orElseThrow()
                .getUserData();
    }
}
