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
 * @param providerOverride an override note for {@code sluice.cull.provider}, or null
 * @param providerUnrecognised a note that the configured provider is not one this install has, or
 *     null when it is
 * @param model {@link String} the configured model id
 * @param modelOverride an override note for the model field, or null
 * @param endpoint {@link String} the configured endpoint
 * @param endpointOverride an override note for the endpoint field, or null
 * @param maxRetries {@link Integer} the configured transport retry count
 * @param maxRetriesOverride an override note for the retries field, or null
 * @param maxRetriesLimit int the largest transport retry count this app accepts
 * @param watchAutomatically boolean whether a waiting cull resumes on its own once ready; false
 *     means it waits for an explicit resume
 * @param watchModeOverride an override note for the watch-mode field, or null
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
 */
public record SettingsView(FolderField workingRoot, FolderField libraryRoot, FolderField inbox, String provider,
                           List<ProviderChoice> providers,
                           @Nullable String providerOverride, @Nullable String providerUnrecognised,
                           @Nullable String model,
                           @Nullable String modelOverride, @Nullable String endpoint,
                           @Nullable String endpointOverride, @Nullable Integer maxRetries,
                           @Nullable String maxRetriesOverride, int maxRetriesLimit,
                           boolean watchAutomatically, @Nullable String watchModeOverride, SecretRow secret, int tileSize,
                           NumberRange tileSizeRange, @Nullable String tileSizeOverride, int tilesPerRow,
                           NumberRange tilesPerRowRange, @Nullable String tilesPerRowOverride, String theme,
                           List<ThemeOption> themes, @Nullable String themeOverride) {

    /**
     * What a number field accepts, decided where the other limits are rather than by whichever
     * control happens to draw it.
     *
     * @param least int the smallest value this app accepts
     * @param most int the largest
     * @param step int how far one press of the arrows moves it
     */
    public record NumberRange(int least, int most, int step) {
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
     * Which settings the selected provider actually uses.
     *
     * <p>A provider that calls a model from inside the app needs a model id, an endpoint and a
     * credential. One whose judgement comes from an agent the user runs needs none of them, and
     * needs a watch mode instead. Showing a provider the other's settings offers a control that
     * cannot do anything, which reads as a promise the app does not keep.
     *
     * @param model boolean whether a model id applies
     * @param endpoint boolean whether a non-default endpoint applies
     * @param retries boolean whether a transport retry count applies
     * @param watchMode boolean whether the waiting-cull watch mode applies
     * @param credential boolean whether a stored credential applies
     */
    public record ProviderFields(boolean model, boolean endpoint, boolean retries,
                                 boolean watchMode, boolean credential) {
    }

    /**
     * One provider a user can route culling through, and which controls it uses.
     *
     * <p>A choice carries its own fields, so picking one in a dropdown answers what to show without
     * asking anything again. Nothing can then be asked about a provider that does not exist.
     *
     * @param id {@link String} the provider id, as {@code sluice.cull.provider} spells it
     * @param label {@link String} the name a screen shows for it
     * @param fields {@link ProviderFields} which settings this provider uses
     */
    public record ProviderChoice(String id, String label, ProviderFields fields) {
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
