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
    // answer it. Nothing is asserted here about a name that IS on the list. Which of those a
    // machine reserves turns on its hardware, so no portable negative control exists to pair with
    // this. ReservedDeviceNames' own Javadoc carries the measurements.
    @EnabledOnOs(OS.WINDOWS)
    @Test
    void windowsMakesCom0AndLpt0OrdinaryFiles(@TempDir final Path dir) throws IOException {
        Files.createFile(dir.resolve("com0"));
        Files.createFile(dir.resolve("lpt0"));

        try (final Stream<Path> listing = Files.list(dir)) {
            final List<String> names = listing.map(entry -> entry.getFileName().toString()).sorted().toList();
            assertThat(names).containsExactly("com0", "lpt0");
        }
    }
}
