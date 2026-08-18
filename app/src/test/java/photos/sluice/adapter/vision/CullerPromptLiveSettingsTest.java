package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.config.SettingsHolder;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.job.WatchMode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Every other test of a cull reader hands it a hand-written CullSettings that answers the same way
// forever. That proves the reader asks, and nothing about a saved value reaching it. This one puts
// the real settings bean behind the port and changes what it holds while the reader is alive.
//
// The grid is the live half. The cards are not, since a run carries the set it was prepped under.
class CullerPromptLiveSettingsTest {

    private static final PathSettings PATHS = new PathSettings("repo", "library", "inbox");

    @Test
    void aSavedCategoryCardLeavesAnAlreadyPreppedRunsPromptUnchanged() {
        final var recorded = List.of(new CullCategory("junk", "worthless shots"));
        final var holder = new SettingsHolder(settings(recorded, MontageConfig.defaults()));
        final var prompt = new CullerPrompt(holder);

        holder.apply(settings(List.of(new CullCategory("receipts", "paper receipts")), MontageConfig.defaults()));

        assertThat(prompt.systemPrompt(recorded)).contains("worthless shots").doesNotContain("paper receipts");
    }

    @Test
    void aSavedMontageGridIsInTheNextPromptWithNothingRestarted() {
        final var cards = List.of(new CullCategory("junk", "worthless shots"));
        final var holder = new SettingsHolder(settings(cards, new MontageConfig(224, 5)));
        final var prompt = new CullerPrompt(holder);
        assertThat(userTurn(prompt)).contains("rows of 5");

        holder.apply(settings(cards, new MontageConfig(224, 9)));

        assertThat(userTurn(prompt)).contains("rows of 9");
    }

    private static String userTurn(final CullerPrompt prompt) {
        return prompt.userTurn("2019-06", "montage-001", 1, 1,
                List.of(new SidecarPhotoEntry(Path.of("D:/sorted/IMG_001.jpg"), "IMG_001.jpg",
                        Instant.parse("2019-06-20T13:00:10Z"), false)));
    }

    private static Settings settings(final List<CullCategory> categories, final MontageConfig montage) {
        return new Settings(PATHS, "anthropic", new CullProviderSettings(null, null, null, null), categories,
                new ExternalAgentSettings(WatchMode.MANUAL), montage, ThemeChoice.SYSTEM);
    }
}
