package photos.sluice.application.port.out;

import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every setting a user can change, as one value. A settings screen edits one of these, the config
 * file is rewritten from one, and the running app reads the current one through {@link LiveSettings}.
 *
 * <p>It implements {@link CullSettings}, so the compiler proves it carries everything a cull needs
 * to read. The folder roots sit in their own component instead, since they are edited and checked
 * as a group.
 *
 * <p>One value rather than four is the point. A save swaps a single reference, so one of these can
 * never hold half of one save and half of another.
 */
public record Settings(PathSettings paths, String provider,
                       Map<String, CullProviderSettings> providerSettingsById,
                       List<CullCategory> categories, ExternalAgentSettings externalAgent,
                       MontageConfig montage, ThemeChoice theme) implements CullSettings {

    // Bounded here for the reason the duplicate-name check is: this is the one value every category
    // set the app runs on arrives as. Each card is a folder and a section of every culling prompt,
    // so an unbounded list is an unbounded prompt. The app ships four, and twenty is far past any
    // set a person would keep.
    private static final int MAX_CATEGORIES = 20;

    /**
     * The most photo categories one install may hold. For a screen that has to refuse a set before
     * building one of these, and say the number.
     *
     * @return int the ceiling
     */
    public static int maxCategories() {
        return MAX_CATEGORIES;
    }

    /**
     * Copies the category list and the settings map, and refuses categories holding two cards under
     * the same name. Two such cards would silently alias one category.
     *
     * <p>The check lives here rather than on either producer. Bound config and an editing screen
     * both arrive as one of these. So this is the one place that sees every category set the app
     * ever runs on.
     *
     * @param paths {@link PathSettings} the three configured folder roots
     * @param provider {@link String} id of the vision provider a cull routes through
     * @param providerSettingsById a {@link Map} of {@link String} to {@link CullProviderSettings}
     *     the connection settings each API-backed provider is configured with, keyed by provider id
     * @param categories a {@link List} of {@link CullCategory} the classification cards a cull routes to
     * @param externalAgent {@link ExternalAgentSettings} tuning for the external-agent provider
     * @param montage {@link MontageConfig} the contact-sheet grid a cull renders
     * @param theme {@link ThemeChoice} the look the user asked for, or to follow the desktop
     */
    public Settings {
        providerSettingsById = Map.copyOf(providerSettingsById);
        categories = List.copyOf(categories);
        if (categories.size() > MAX_CATEGORIES) {
            throw new IllegalArgumentException("More than the " + MAX_CATEGORIES
                    + " photo categories one install may hold: " + categories.size());
        }
        final List<String> duplicates = categories.stream()
                .collect(Collectors.groupingBy(CullCategory::name, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Photo categories contain duplicate name(s): " + String.join(", ", duplicates));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CullProviderSettings providerSettings() {
        return this.providerSettings(this.provider);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CullProviderSettings providerSettings(final String providerId) {
        return this.providerSettingsById.getOrDefault(providerId, CullProviderSettings.unset());
    }
}
