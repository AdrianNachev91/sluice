package photos.sluice.adapter.vision;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembles the text of an automated culling request. The system prompt is the bundled fixed
 * template with the configured category cards rendered into its placeholder. The template owns
 * the prompt engineering. A card contributes only its plain-words name and description.
 *
 * <p>The user turn is built per montage from its sidecar entries. Grid dimensions come from the
 * injected {@link MontageConfig} because the sidecar records no grid info. Prompt assembly
 * assumes a cull runs with the same montage config that rendered its prep directory.
 */
@Component
class CullerPrompt {

    static final String CATEGORIES_PLACEHOLDER = "{{categories}}";
    private static final String TEMPLATE_RESOURCE = "/cull/culler-prompt.md";

    private final CullSettings settings;
    private final MontageConfig montageConfig;
    private final String template;

    /**
     * Constructs the prompt builder, loading the bundled template.
     *
     * @param settings {@link CullSettings} the cull settings, supplying the configured categories
     * @param montageConfig {@link MontageConfig} the montage grid configuration
     */
    CullerPrompt(final CullSettings settings, final MontageConfig montageConfig) {
        this.settings = settings;
        this.montageConfig = montageConfig;
        this.template = loadTemplate();
    }

    /**
     * The system prompt shared by every montage in a run. No configured cards would render a
     * prompt with no set-aside classes at all, silently gutting the cull's rule set - fail loud
     * instead.
     *
     * @return {@link String} the rendered system prompt
     */
    String systemPrompt() {
        if (this.settings.categories().isEmpty()) {
            throw new IllegalStateException("No cull categories configured (sluice.cull.categories); "
                    + "an automated cull needs at least one");
        }
        return rendered(this.template, this.settings.categories());
    }

    /**
     * One montage's user turn: sheet header, numbering scheme, and the photo table the model keys
     * its verdicts to. Indices are 1-based in sidecar order, which is grid order. montage is the
     * sidecar's base name (montage-NNN); ordinal and total locate the sheet within the run. The
     * table labels each timestamp "taken" although the sidecar carries raw file mtime. The plain
     * word keeps the burst-grouping rule natural for the model, and mtime is the closest signal
     * the sidecar has.
     *
     * @param scope {@link String} the cull scope
     * @param montage {@link String} the montage's base name
     * @param ordinal int the montage's 1-based position in the run
     * @param total int the total number of montages in the run
     * @param entries a {@link List} of {@link SidecarPhotoEntry}, the montage's sidecar photo entries
     * @return {@link String} the rendered user turn text
     */
    String userTurn(final String scope, final String montage, final int ordinal, final int total, final List<SidecarPhotoEntry> entries) {
        final var text = new StringBuilder();
        text.append("Scope: ").append(scope)
                .append(" - sheet ").append(montage.replaceFirst("^montage-", ""))
                .append(" (").append(ordinal).append(" of ").append(total).append(")\n");
        text.append("Grid: ").append(entries.size()).append(" photos in rows of ")
                .append(this.montageConfig.tilesPerRow())
                .append(", numbered left-to-right then top-to-bottom.\n");
        text.append("Photos:\n");
        for (int i = 0; i < entries.size(); i++) {
            final SidecarPhotoEntry entry = entries.get(i);
            text.append(i + 1).append(". ").append(entry.name()).append(" | taken ").append(entry.time());
            if (entry.received()) {
                text.append(" | received");
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * The follow-up turn after a response failed validation. It lists every problem found and asks
     * for the whole corrected verdict list, not a patch. The response replaces the failed one
     * outright, so a partial answer would drop the verdicts it omits. The reply's shape is also
     * restated defensively. A name-mismatch problem can read as if the misnamed photo were an
     * extra photo, and a model that answers for both names duplicates an index.
     *
     * @param problems a {@link List} of {@link String}, validation problems found in the failed response
     * @return {@link String} the rendered correction turn text
     */
    String correctionTurn(final List<String> problems) {
        final var text = new StringBuilder();
        text.append("Your verdicts for this sheet failed validation:\n");
        for (final String problem : problems) {
            text.append(" - ").append(problem).append('\n');
        }
        text.append("Return the complete corrected verdict list for this sheet as JSON only, "
                + "matching the schema you were given. Give exactly one verdict per photo in the "
                + "photo table, keyed by that table's index and name. Never add a verdict for any "
                + "other index or name.\n");
        return text.toString();
    }

    /**
     * Package-private static so the placeholder contract is testable without swapping out the
     * bundled resource.
     *
     * @param template {@link String} the prompt template containing the categories placeholder
     * @param categories a {@link List} of {@link CullCategory}, the configured cull categories to render into the template
     * @return {@link String} the template with the categories placeholder replaced
     */
    static String rendered(final String template, final List<CullCategory> categories) {
        if (!template.contains(CATEGORIES_PLACEHOLDER)) {
            throw new IllegalStateException("Prompt template " + TEMPLATE_RESOURCE
                    + " lacks the " + CATEGORIES_PLACEHOLDER + " placeholder");
        }
        final String cards = categories.stream()
                .map(card -> "### `" + card.name() + "`\n\n" + card.description())
                .collect(Collectors.joining("\n\n"));
        return template.replace(CATEGORIES_PLACEHOLDER, cards);
    }

    /**
     * The template ships inside the jar, so a missing or unreadable one is a packaging defect.
     * Failing in the constructor surfaces that at startup rather than mid-cull.
     *
     * @return {@link String} the bundled prompt template's text
     */
    private static String loadTemplate() {
        try (final var input = CullerPrompt.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Bundled prompt template " + TEMPLATE_RESOURCE + " is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read bundled prompt template " + TEMPLATE_RESOURCE, e);
        }
    }
}
