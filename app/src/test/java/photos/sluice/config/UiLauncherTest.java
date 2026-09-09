package photos.sluice.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.ui.UiBootstrap;
import photos.sluice.application.port.out.ConfigFileRepairPort;
import photos.sluice.application.port.out.WorkingRootBusyException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UiLauncherTest {

    // What another class installed would otherwise stand in for what this one is testing, since the
    // holder is process-wide and the suite runs in one process.
    @BeforeEach
    @AfterEach
    void forgetWhatWasInstalled() {
        UiBootstrap.clear();
    }

    // Recognising the refusal is what proves a real classifier landed. Nothing installed would
    // answer the same way for every failure, and pass a weaker assertion.
    @Test
    void launchingInstallsWhatTheWindowNeedsIfStartupFails(@TempDir final Path dir) {
        UiLauncher.installFailureHandling(dir.resolve("config.yml"));

        assertThat(UiBootstrap.reportAndPresent(new WorkingRootBusyException(dir)).card().detail())
                .contains("Another Sluice process is already running");
    }

    @Test
    void theInstalledRepairActsOnTheFileTheLaunchImports(@TempDir final Path dir) throws IOException {
        final Path configFile = dir.resolve("config.yml");
        Files.writeString(configFile, "sluice:\n  montage:\n    tile-size: [1, 2\n");

        UiLauncher.installFailureHandling(configFile);

        final ConfigFileRepairPort repair = UiBootstrap.repair();
        assertThat(repair).isNotNull();
        assertThat(repair.setAside()).hasParent(dir)
                .satisfies(moved -> assertThat(moved.getFileName().toString()).startsWith("config.broken-"));
    }
}
