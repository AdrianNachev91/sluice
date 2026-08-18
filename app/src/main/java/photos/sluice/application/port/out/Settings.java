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
public record Settings(PathSettings paths, String provider, CullProviderSettings providerSettings,
                       List<CullCategory> categories, ExternalAgentSettings externalAgent,
                       MontageConfig montage, ThemeChoice theme) implements CullSettings {

    /**
     * Copies the category list, and refuses one holding two cards under the same name. Two such
     * cards would silently alias one category.
     *
     * <p>The check lives here rather than on either producer. Bound config and an editing screen
     * both arrive as one of these. So this is the one place that sees every category set the app
     * ever runs on.
     *
     * @param paths {@link PathSettings} the three configured folder roots
     * @param provider {@link String} id of the vision provider a cull routes through
     * @param providerSettings {@link CullProviderSettings} connection settings for API-backed providers
     * @param categories a {@link List} of {@link CullCategory} the classification cards a cull routes to
     * @param externalAgent {@link ExternalAgentSettings} tuning for the external-agent provider
     * @param montage {@link MontageConfig} the contact-sheet grid a cull renders
     * @param theme {@link ThemeChoice} the look the user asked for, or to follow the desktop
     */
    public Settings {
        categories = List.copyOf(categories);
        final List<String> duplicates = categories.stream()
                .collect(Collectors.groupingBy(CullCategory::name, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cull categories contain duplicate name(s): " + String.join(", ", duplicates));
        }
    }
}
