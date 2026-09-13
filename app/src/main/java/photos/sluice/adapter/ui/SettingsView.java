package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the Settings screen draws, chosen from a {@link SettingsPresenter} and carrying only
 * display-ready values. The view reads fields off this and decides nothing about what they mean.
 *
 * @param workingRoot {@link FolderField} the working root row
 * @param libraryRoot {@link FolderField} the library root row
 * @param inbox {@link FolderField} the inbox row
 * @param provider {@link String} the configured provider id
 * @param providers a {@link List} of {@link ProviderChoice} every provider a user can pick, each
 *     carrying which settings it uses
 * @param providerOverride an override note for {@code sluice.sift.provider}, or null
 * @param providerUnrecognised a note that the configured provider is not one this install has, or
 *     null when it is
 * @param model {@link ModelPicker} what the model picker offers and shows selected, or null for a
 *     provider with no model setting
 * @param modelOverride an override note for the model field, or null
 * @param modelUnrecognised a note that the configured model is not one the shown catalog offers, or
 *     null when it is, or when nothing is configured
 * @param endpoint {@link String} the configured endpoint
 * @param endpointOverride an override note for the endpoint field, or null
 * @param secret {@link SecretRow} the credential row, for the provider it belongs to
 * @param tileSize int the configured montage tile size
 * @param tileSizeRange {@link NumberRange} what a tile size is allowed to be
 * @param tileSizeOverride an override note for the tile-size field, or null
 * @param tilesPerRow int the configured montage tiles-per-row
 * @param tilesPerRowRange {@link NumberRange} what a tiles-per-row count is allowed to be
 * @param tilesPerRowOverride an override note for the tiles-per-row field, or null
 * @param theme {@link String} id of the look the user asked for, matching a {@link ThemeOption}
 * @param themes a {@link List} of {@link ThemeOption} every look a user can pick
 * @param themeOverride an override note for the theme field, or null
 * @param endpointLimit int the most the endpoint field may hold
 * @param rootLimit int the most a folder-root field may hold
 * @param keyLimit int the most the credential field may hold
 */
public record SettingsView(FolderField workingRoot, FolderField libraryRoot, FolderField inbox, String provider,
                           List<ProviderChoice> providers,
                           @Nullable String providerOverride, @Nullable String providerUnrecognised,
                           @Nullable ModelPicker model,
                           @Nullable String modelOverride, @Nullable String modelUnrecognised,
                           @Nullable String endpoint,
                           @Nullable String endpointOverride,
                           SecretRow secret, int tileSize,
                           NumberRange tileSizeRange, @Nullable String tileSizeOverride, int tilesPerRow,
                           NumberRange tilesPerRowRange, @Nullable String tilesPerRowOverride, String theme,
                           List<ThemeOption> themes, @Nullable String themeOverride,
                           int endpointLimit, int rootLimit, int keyLimit) {

    /**
     * What a number field accepts, decided where the other limits are rather than by whichever
     * control happens to draw it.
     *
     * @param min int the smallest value this app accepts
     * @param max int the largest
     * @param step int how far one press of the arrows moves it
     */
    public record NumberRange(int min, int max, int step) {
    }

    /**
     * One look a user can pick, including following the desktop.
     *
     * @param id {@link String} the choice's own name, as {@code sluice.ui.theme} spells it
     * @param label {@link String} the name a screen shows for it
     */
    public record ThemeOption(String id, String label) {
    }

    /**
     * What the model picker draws: a choice to make, or nothing to choose from and why.
     *
     * <p>A provider's own static list before any key has been checked, and the account's real list
     * after a successful check, both draw as {@link Options}. They differ only in which models are
     * offered and in {@link Options#sourceNote}, which says which list the reader is looking at.
     * The check made as the app opens draws as {@link Pending} until it settles. Every other outcome
     * draws as {@link Unavailable}, with nothing to select. The key was rejected, the account can
     * run nothing Sluice needs, or the service could not be reached.
     */
    public sealed interface ModelPicker {

        /**
         * A list to choose from.
         *
         * @param choices a {@link List} of {@link ModelChoice} every model offered
         * @param selected {@link String} id of the choice to show selected, one of {@code choices}
         * @param sourceNote {@link String} which list this is: the provider's own guess, or the
         *     account's real one
         */
        record Options(List<ModelChoice> choices, String selected, String sourceNote) implements ModelPicker {
        }

        /**
         * Nothing to choose from.
         *
         * @param reason {@link String} what went wrong, in the provider's own words where it has
         *     them
         * @param savedModel the model already configured, or null where none is. Carried because
         *     the picker cannot hold it. An empty picker is what says nothing here was confirmed,
         *     so the saved model is named beside it rather than sat in it
         */
        record Unavailable(String reason, @Nullable String savedModel) implements ModelPicker {
        }

        /**
         * Nothing to show yet, because nothing has answered.
         *
         * <p>Separate from {@link Unavailable} because nothing has gone wrong. There is no
         * violation to word and nothing for a reader to retry, only an answer on its way. A screen
         * drawing this owes the reader whatever replaces it, since the wait is what it promised.
         *
         * @param message {@link String} what is happening, drawn in the picker itself rather than
         *     beside it. A line under the control is read by nobody in the seconds it is up
         */
        record Pending(String message) implements ModelPicker {
        }
    }

    /**
     * One model a picker can offer.
     *
     * @param id {@link String} the model id, as a saved setting spells it
     * @param label {@link String} the name a screen shows for it
     * @param recommended boolean whether this is the provider's own recommendation
     */
    public record ModelChoice(String id, String label, boolean recommended) {
    }

    /**
     * Which settings the selected provider actually uses.
     *
     * <p>A provider that calls a model from inside the app needs a model id, an endpoint and a
     * credential. One whose judgement comes from an agent the user runs needs none of them. Showing
     * a provider the other's settings offers a control that cannot do anything, which reads as a
     * promise the app does not keep.
     *
     * @param model boolean whether a model id applies
     * @param endpoint boolean whether a non-default endpoint applies
     * @param credential boolean whether a stored credential applies
     */
    public record ProviderFields(boolean model, boolean endpoint, boolean credential) {
    }

    /**
     * One provider a user can route culling through, and which controls it uses.
     *
     * <p>A choice carries its own fields, so picking one in a dropdown answers what to show without
     * asking anything again. Nothing can then be asked about a provider that does not exist.
     *
     * @param id {@link String} the provider id, as {@code sluice.sift.provider} spells it
     * @param label {@link String} the name a screen shows for it
     * @param fields {@link ProviderFields} which settings this provider uses
     * @param defaultEndpoint {@link String} what an empty endpoint field actually reaches, or null
     *     when either this provider takes no endpoint or it has none worth naming
     * @param setupGuide {@link String} where somebody with no credential yet goes to get one, or
     *     null when this provider takes none or has nowhere to send them
     */
    public record ProviderChoice(String id, String label, ProviderFields fields,
                                 @Nullable String defaultEndpoint, @Nullable String setupGuide) {
    }

    /**
     * One folder-root field: what is saved, what a picker would open on if the field is empty, and
     * what is wrong with it, if anything.
     *
     * @param value {@link String} the configured text, exactly as typed, empty when unset
     * @param suggestion {@link String} the folder a picker opens on when nothing is typed yet
     * @param violation what is wrong with this root right now, or null when it is usable or unset
     */
    public record FolderField(String value, String suggestion, @Nullable String violation) {
    }

    /**
     * The credential row, and whatever that credential's own state calls for. Never carries the
     * credential itself.
     *
     * <p>Where a key is stored is said once, by the reassurance under the entry. Saying it again
     * above the field only reads as two facts when they differ, and they differ by tense.
     *
     * @param reassurance {@link String} the line under the entry field, naming where a save would land
     * @param environmentOverride the note shown when the environment variable outranks anything saved,
     *     or null
     * @param multiHolder the note shown when more than one place holds a key, or null
     * @param errorMessage a broken-store message in place of the ordinary row, or null when the row
     *     reads normally
     * @param hasStoredValue boolean whether a save exists to remove, so Remove can be disabled otherwise
     */
    public record SecretRow(String reassurance, @Nullable String environmentOverride,
                            @Nullable String multiHolder, @Nullable String errorMessage, boolean hasStoredValue) {
    }
}
