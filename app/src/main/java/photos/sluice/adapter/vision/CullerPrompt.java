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

// Assembles the text of an automated culling request. The system prompt is the bundled fixed
// template with the configured category cards rendered into its placeholder. The template owns
// the prompt engineering; a card contributes only its plain-words name and description. The user
// turn is built per montage from its sidecar entries. Grid dimensions come from the injected
// MontageConfig because the sidecar records no grid info. Prompt assembly assumes a cull runs
// with the same montage config that rendered its prep directory.
@Component
class CullerPrompt {

    static final String CATEGORIES_PLACEHOLDER = "{{categories}}";
    private static final String TEMPLATE_RESOURCE = "/cull/culler-prompt.md";

    private final CullSettings settings;
    private final MontageConfig montageConfig;
    private final String template;

    CullerPrompt(CullSettings settings, MontageConfig montageConfig) {
        this.settings = settings;
        this.montageConfig = montageConfig;
        this.template = loadTemplate();
    }

    // The system prompt shared by every montage in a run. No configured cards would render a
    // prompt with no set-aside classes at all, silently gutting the cull's rule set - fail loud
    // instead.
    String systemPrompt() {
        if (settings.categories().isEmpty()) {
            throw new IllegalStateException("No cull categories configured (sluice.cull.categories); "
                    + "an automated cull needs at least one");
        }
        return rendered(template, settings.categories());
    }

    // One montage's user turn: sheet header, numbering scheme, and the photo table the model keys
    // its verdicts to. Indices are 1-based in sidecar order, which is grid order. montage is the
    // sidecar's base name (montage-NNN); ordinal and total locate the sheet within the run.
    String userTurn(String scope, String montage, int ordinal, int total, List<SidecarPhotoEntry> entries) {
        var text = new StringBuilder();
        text.append("Scope: ").append(scope)
                .append(" - sheet ").append(montage.replaceFirst("^montage-", ""))
                .append(" (").append(ordinal).append(" of ").append(total).append(")\n");
        text.append("Grid: ").append(entries.size()).append(" photos in rows of ")
                .append(montageConfig.tilesPerRow())
                .append(", numbered left-to-right then top-to-bottom.\n");
        text.append("Photos:\n");
        for (int i = 0; i < entries.size(); i++) {
            SidecarPhotoEntry entry = entries.get(i);
            text.append(i + 1).append(". ").append(entry.name()).append(" | taken ").append(entry.time());
            if (entry.received()) {
                text.append(" | received");
            }
            text.append('\n');
        }
        return text.toString();
    }

    // Package-private static so the placeholder contract is testable without swapping out the
    // bundled resource.
    static String rendered(String template, List<CullCategory> categories) {
        if (!template.contains(CATEGORIES_PLACEHOLDER)) {
            throw new IllegalStateException("Prompt template " + TEMPLATE_RESOURCE
                    + " lacks the " + CATEGORIES_PLACEHOLDER + " placeholder");
        }
        String cards = categories.stream()
                .map(card -> "### `" + card.name() + "`\n\n" + card.description())
                .collect(Collectors.joining("\n\n"));
        return template.replace(CATEGORIES_PLACEHOLDER, cards);
    }

    // The template ships inside the jar, so a missing or unreadable one is a packaging defect.
    // Failing in the constructor surfaces that at startup rather than mid-cull.
    private static String loadTemplate() {
        try (var input = CullerPrompt.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Bundled prompt template " + TEMPLATE_RESOURCE + " is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled prompt template " + TEMPLATE_RESOURCE, e);
        }
    }
}
