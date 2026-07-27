package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class DisasterDrawerTest {

    @Test
    void filesASourceIntoThePrepDirsDisastersFolderEmbeddingTheGivenWhatAndExtension(@TempDir Path root) throws IOException {
        Path prepDir = root.resolve("logs/cull-prep/2019-06");
        Path source = writeFile(root.resolve("move-records.log"), "stale content");

        Path filed = drawer().file(prepDir, source, "move-records-log");

        assertThat(filed.getParent()).isEqualTo(prepDir.resolve("disasters"));
        assertThat(filed.getFileName().toString()).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-move-records-log\\.log");
        assertThat(Files.exists(source)).isFalse();
        assertThat(Files.readString(filed)).isEqualTo("stale content");
    }

    @Test
    void filingTwoEventsUnderTheSameWhatGetsTheSecondANumericSuffixInsteadOfOverwritingTheFirst(@TempDir Path root)
            throws IOException {
        Path prepDir = root.resolve("logs/cull-prep/2019-06");
        DisasterDrawer drawer = drawer();
        Path firstSource = writeFile(root.resolve("first.log"), "first");
        Path secondSource = writeFile(root.resolve("second.log"), "second");

        Path firstFiled = drawer.file(prepDir, firstSource, "move-records-log");
        Path secondFiled = drawer.file(prepDir, secondSource, "move-records-log");

        assertThat(firstFiled).isNotEqualTo(secondFiled);
        assertThat(Files.readString(firstFiled)).isEqualTo("first");
        assertThat(Files.readString(secondFiled)).isEqualTo("second");
    }

    @Test
    void writesContentAsANewTimestampedDrawerEntry(@TempDir Path root) throws IOException {
        Path prepDir = root.resolve("logs/cull-prep/2019-06");

        Path written = drawer().write(prepDir, "troubleshoot-report", "line one\nline two");

        assertThat(written.getParent()).isEqualTo(prepDir.resolve("disasters"));
        assertThat(written.getFileName().toString()).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-troubleshoot-report\\.txt");
        assertThat(Files.readAllLines(written)).containsExactly("line one", "line two");
    }

    @Test
    void writingTwoReportsGetsTheSecondANumericSuffixInsteadOfOverwritingTheFirst(@TempDir Path root) {
        Path prepDir = root.resolve("logs/cull-prep/2019-06");
        DisasterDrawer drawer = drawer();

        Path first = drawer.write(prepDir, "troubleshoot-report", "first");
        Path second = drawer.write(prepDir, "troubleshoot-report", "second");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void sweepExpiredDeletesOnlyDrawerEntriesOlderThanThirtyDays(@TempDir Path root) throws IOException {
        Path cullPrepRoot = root.resolve("logs/cull-prep");
        Path drawer1 = cullPrepRoot.resolve("2019-06/disasters");
        Path oldEntry = writeFile(drawer1.resolve("2019-01-01_00-00-00-move-records-log.log"), "old");
        Path freshEntry = writeFile(drawer1.resolve(recentStampedName()), "fresh");
        // A sibling non-drawer file at the same nesting depth, to prove the sweep doesn't wander
        // outside disasters/ folders.
        Path unrelated = writeFile(cullPrepRoot.resolve("2019-06/index.json"), "{}");

        int deleted = drawer().sweepExpired(cullPrepRoot);

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(oldEntry)).isFalse();
        assertThat(Files.exists(freshEntry)).isTrue();
        assertThat(Files.exists(unrelated)).isTrue();
    }

    @Test
    void sweepExpiredLeavesAnUnparseableFilenameAloneRatherThanGuessing(@TempDir Path root) throws IOException {
        Path cullPrepRoot = root.resolve("logs/cull-prep");
        Path unparseable = writeFile(cullPrepRoot.resolve("2019-06/disasters/not-a-timestamped-name.log"), "?");

        int deleted = drawer().sweepExpired(cullPrepRoot);

        assertThat(deleted).isZero();
        assertThat(Files.exists(unparseable)).isTrue();
    }

    @Test
    void sweepExpiredOnAMissingRootReturnsZeroWithoutThrowing(@TempDir Path root) {
        int deleted = drawer().sweepExpired(root.resolve("logs/cull-prep"));

        assertThat(deleted).isZero();
    }

    // A timestamp comfortably inside the 30-day retention window, so this entry must survive a sweep.
    private static String recentStampedName() {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);
        return format.format(Instant.now().minus(Duration.ofDays(1))) + "-move-records-log.log";
    }

    private static Path writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static DisasterDrawer drawer() {
        return new DisasterDrawer(new NioMediaStore());
    }
}
