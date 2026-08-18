package photos.sluice.config;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.domain.cull.CullCategory;
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
    // Nullable throughout, since that is how an install with nothing chosen yet carries them.
    public static PathsConfig pathsConfig(final @Nullable String repoRoot, final @Nullable String libraryRoot,
                                          final @Nullable String inbox) {
        return new PathsConfig(holder(repoRoot, libraryRoot, inbox));
    }

    // For a test wiring two collaborators that read the same settings, such as a paths config and
    // the validation over it. Both take this one holder, as they take one bean in production.
    public static SettingsHolder holder(final Path repoRoot, final Path libraryRoot, final Path inbox) {
        return holder(repoRoot.toString(), libraryRoot.toString(), inbox.toString());
    }

    public static SettingsHolder holder(final @Nullable String repoRoot, final @Nullable String libraryRoot,
                                        final @Nullable String inbox) {
        return new SettingsHolder(settings(new PathSettings(repoRoot, libraryRoot, inbox)));
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
                new ExternalAgentSettings(WatchMode.MANUAL), MontageConfig.defaults(), ThemeChoice.SYSTEM);
    }
}
