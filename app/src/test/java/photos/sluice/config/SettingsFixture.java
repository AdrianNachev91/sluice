package photos.sluice.config;

import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;

import java.nio.file.Path;
import java.util.List;

// Settings values and a live PathsConfig over them, for tests that build a collaborator directly
// instead of through Spring.
public final class SettingsFixture {

    private SettingsFixture() {
    }

    public static PathsConfig pathsConfig(final Path repoRoot, final Path libraryRoot, final Path inbox) {
        return pathsConfig(repoRoot.toString(), libraryRoot.toString(), inbox.toString());
    }

    // The roots as a config file would carry them, for a test that cares how a raw value resolves.
    public static PathsConfig pathsConfig(final String repoRoot, final String libraryRoot, final String inbox) {
        return new PathsConfig(new SettingsHolder(settings(new PathSettings(repoRoot, libraryRoot, inbox))));
    }

    // For a test that reads only the directories derived from the working root, such as logs or
    // Sorted. The library and inbox roots are placeholders under it.
    public static PathsConfig workingRoot(final Path repoRoot) {
        return pathsConfig(repoRoot, repoRoot.resolve("Library"), repoRoot.resolve("Inbox"));
    }

    // The given roots, on the cull defaults a fresh install starts with.
    public static Settings settings(final PathSettings paths) {
        return new Settings(paths, "external-agent", new CullProviderSettings(null, null, null, null),
                List.of(new CullCategory("junk", "junk description"),
                        new CullCategory("scenery", "scenery description"),
                        new CullCategory("food", "food description"),
                        new CullCategory("funny", "funny description")),
                new ExternalAgentSettings(WatchMode.MANUAL), MontageConfig.defaults());
    }
}
