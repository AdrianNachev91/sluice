package photos.sluice.adapter.vision;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.CullJudgement;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembles the text of an automated culling request. The system prompt is the bundled fixed
 * template with two things rendered into it. One is the run's category cards, each contributing
 * only its plain-words name and description. The other is {@link CullJudgement}, which the
 * reader's own agent is handed word for word as well. The template owns the rest of the prompt
 * engineering.
 *
 * <p>The cards are a parameter rather than a settings read. They come from the prep dir the run is
 * culling, the same recorded set the response is then validated against. Rendering from live config
 * would let the prompt describe one rule set while validation judged another.
 *
 * <p>Grid dimensions come from the cull settings because the sidecar records no grid info. Prompt
 * assembly therefore assumes a cull runs with the same montage config that rendered its prep
 * directory.
 */
@Component
class CullerPrompt {

    static final String CATEGORIES_PLACEHOLDER = "{{categories}}";
    static final String JUDGEMENT_PLACEHOLDER = "{{judgement}}";
    static final String GROUP_SLUG_MAX_PLACEHOLDER = "{{groupSlugMax}}";
    static final String FILLER_WORDS_PLACEHOLDER = "{{fillerWords}}";
    private static final String TEMPLATE_RESOURCE = "/cull/culler-prompt.md";

    private final CullSettings settings;
    private final String template;

    /**
     * Constructs the prompt builder, loading the bundled template.
     *
     * @param settings {@link CullSettings} the cull settings, supplying the montage grid
     */
    CullerPrompt(final CullSettings settings) {
        this.settings = settings;
        this.template = loadTemplate();
    }

    /**
     * The system prompt shared by every montage in a run. An empty card set would render a prompt
     * with no set-aside classes at all, silently gutting the cull's rule set - fail loud instead.
     * The refusal names no remedy. Getting a card into this run means re-prepping the scope, which
     * discards any shards already bought for it, so that is the user's call rather than advice.
     *
     * @param categories a {@link List} of {@link CullCategory}, the cards the run recorded at prep time
     * @return {@link String} the rendered system prompt
     */
    String systemPrompt(final List<CullCategory> categories) {
        if (categories.isEmpty()) {
            throw new IllegalStateException("This run recorded no photo categories, and there is nothing "
                    + "to sort photos into without them");
        }
        return render(this.template, categories);
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
    String userTurn(final String scope, final String montage, final int ordinal, final int total,
                    final List<SidecarPhotoEntry> entries) {
        final var text = new StringBuilder();
        text.append("Scope: ").append(scope)
                .append(" - sheet ").append(montage.replaceFirst("^montage-", ""))
                .append(" (").append(ordinal).append(" of ").append(total).append(")\n");
        text.append("Grid: ").append(entries.size()).append(" photos in rows of ")
                .append(this.settings.montage().tilesPerRow())
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
        // The system prompt already asks for a verdict per numbered photo. That is its one rule
        // quantifying over a set the model would have to count for itself. So it is restated here
        // beside the list it governs, with the count already in hand.
        text.append("Return exactly ").append(entries.size())
                .append(" verdicts, one for each numbered photo above. Use indexes 1 to ")
                .append(entries.size())
                .append(" only, and do not add a verdict for any index or name not in this ")
                .append("list.\n");
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
     * @param template {@link String} the prompt template containing both placeholders
     * @param categories a {@link List} of {@link CullCategory}, the run's cull categories to render into the
     * template
     * @return {@link String} the template with both placeholders replaced
     */
    static String render(final String template, final List<CullCategory> categories) {
        requirePlaceholder(template, CATEGORIES_PLACEHOLDER);
        requirePlaceholder(template, JUDGEMENT_PLACEHOLDER);
        requirePlaceholder(template, GROUP_SLUG_MAX_PLACEHOLDER);
        requirePlaceholder(template, FILLER_WORDS_PLACEHOLDER);
        final String cards = categories.stream()
                .map(CullerPrompt::card)
                .collect(Collectors.joining("\n\n"));
        // Rendered rather than written into the template, so the limit a model is asked for and the
        // limit the validator enforces cannot drift. A prompt asking for more than the validator
        // allows buys its refusal once the model has already been paid.
        return template.replace(CATEGORIES_PLACEHOLDER, cards)
                .replace(JUDGEMENT_PLACEHOLDER, CullJudgement.TEXT)
                .replace(GROUP_SLUG_MAX_PLACEHOLDER, String.valueOf(ShardValidator.groupSlugMaxLength()))
                .replace(FILLER_WORDS_PLACEHOLDER, ShardValidator.fillerWords().stream()
                        .map(word -> "`" + word + "`")
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Refuses a template that has lost one of its placeholders. A rendered prompt missing its
     * judgement rules or its category set still reads as a plausible request, and the model would
     * answer it. The answer would be judged against rules it was never given.
     *
     * @param template {@link String} the prompt template
     * @param placeholder {@link String} the placeholder that has to be in it
     */
    private static void requirePlaceholder(final String template, final String placeholder) {
        if (!template.contains(placeholder)) {
            throw new IllegalStateException("Prompt template " + TEMPLATE_RESOURCE
                    + " lacks the " + placeholder + " placeholder");
        }
    }

    /**
     * One card's prompt section: its name as a heading, its description, and its examples as a list
     * under it.
     *
     * <p>A card offering none appends nothing at all, so nothing downstream has to be trimmed back
     * off. A description can arrive carrying its own trailing newline, from a plain {@code >} or
     * {@code |} block in the config file, and that whitespace is the author's to keep.
     *
     * @param card {@link CullCategory} the category card to render
     * @return {@link String} the card's markdown section
     */
    private static String card(final CullCategory card) {
        final var section = new StringBuilder("### `").append(card.name()).append("`\n\n")
                .append(card.description());
        if (!card.examples().isEmpty()) {
            section.append("\n\nExamples:");
            for (final String example : card.examples()) {
                section.append("\n- ").append(example);
            }
        }
        return section.toString();
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
            throw new IllegalStateException("Bundled prompt template " + TEMPLATE_RESOURCE
                    + " could not be read", e);
        }
    }
}
