package photos.sluice.domain.paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReservedDeviceNamesTest {

    @Test
    void namesTheDevicesWindowsReserves() {
        assertThat(ReservedDeviceNames.isReserved("con")).isTrue();
        assertThat(ReservedDeviceNames.isReserved("prn")).isTrue();
        assertThat(ReservedDeviceNames.isReserved("aux")).isTrue();
        assertThat(ReservedDeviceNames.isReserved("nul")).isTrue();
        assertThat(ReservedDeviceNames.isReserved("com1")).isTrue();
        assertThat(ReservedDeviceNames.isReserved("com9")).isTrue();
        assertThat(ReservedDeviceNames.isReserved("lpt1")).isTrue();
        assertThat(ReservedDeviceNames.isReserved("lpt9")).isTrue();
    }

    @Test
    void matchesWhicheverCaseTheNameIsWrittenIn() {
        assertThat(ReservedDeviceNames.isReserved("CON")).isTrue();
        assertThat(ReservedDeviceNames.isReserved("Com1")).isTrue();
    }

    @Test
    void leavesANameThatMerelyStartsWithADeviceAlone() {
        assertThat(ReservedDeviceNames.isReserved("console")).isFalse();
        assertThat(ReservedDeviceNames.isReserved("nullify")).isFalse();
        assertThat(ReservedDeviceNames.isReserved("com10")).isFalse();
        assertThat(ReservedDeviceNames.isReserved("com0")).isFalse();
        assertThat(ReservedDeviceNames.isReserved("lpt0")).isFalse();
    }

    // The list omits com0 and lpt0 on a claim about Windows itself, and only a real Windows can
    // answer it. The com1 half is the control: without it, a build reserving nothing at all would
    // pass the com0 half and look like confirmation. Written against 10.0.19045; the CI runner is
    // a later build, so this is where a divergence would first show.
    @EnabledOnOs(OS.WINDOWS)
    @Test
    void windowsMakesCom0AnOrdinaryFileAndKeepsCom1ForTheDevice(@TempDir final Path dir) throws IOException {
        Files.createFile(dir.resolve("com0"));
        Files.createFile(dir.resolve("lpt0"));
        try {
            Files.createFile(dir.resolve("com1"));
        } catch (final IOException reserved) {
            // Windows either refuses outright or routes the create to the device. Either way it
            // leaves no directory entry, which is what the assertion below reads.
        }

        try (final Stream<Path> listing = Files.list(dir)) {
            final List<String> names = listing.map(entry -> entry.getFileName().toString()).sorted().toList();
            assertThat(names).containsExactly("com0", "lpt0");
        }
    }
}
