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
    void filesASourceIntoThePrepDirsDisastersFolderEmbeddingTheGivenWhatAndExtension(@TempDir final Path root) throws IOException {
        final Path prepDir = root.resolve("logs/cull-prep/2019-06");
        final Path source = writeFile(root.resolve("move-records.log"), "stale content");

        final Path filed = drawer().file(prepDir, source, "move-records-log");

        assertThat(filed.getParent()).isEqualTo(prepDir.resolve("disasters"));
        assertThat(filed.getFileName().toString()).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-move-records" +
                "-log\\.log");
        assertThat(Files.exists(source)).isFalse();
        assertThat(Files.readString(filed)).isEqualTo("stale content");
    }

    @Test
    void filingTwoEventsUnderTheSameWhatGetsTheSecondANumericSuffixInsteadOfOverwritingTheFirst(@TempDir final Path root)
            throws IOException {
        final Path prepDir = root.resolve("logs/cull-prep/2019-06");
        final DisasterDrawer drawer = drawer();
        final Path firstSource = writeFile(root.resolve("first.log"), "first");
        final Path secondSource = writeFile(root.resolve("second.log"), "second");

        final Path firstFiled = drawer.file(prepDir, firstSource, "move-records-log");
        final Path secondFiled = drawer.file(prepDir, secondSource, "move-records-log");

        assertThat(firstFiled).isNotEqualTo(secondFiled);
        assertThat(Files.readString(firstFiled)).isEqualTo("first");
        assertThat(Files.readString(secondFiled)).isEqualTo("second");
    }

    @Test
    void writesContentAsANewTimestampedDrawerEntry(@TempDir final Path root) throws IOException {
        final Path prepDir = root.resolve("logs/cull-prep/2019-06");

        final Path written = drawer().write(prepDir, "troubleshoot-report", "line one\nline two");

        assertThat(written.getParent()).isEqualTo(prepDir.resolve("disasters"));
        assertThat(written.getFileName().toString()).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-troubleshoot" +
                "-report\\.txt");
        assertThat(Files.readAllLines(written)).containsExactly("line one", "line two");
    }

    @Test
    void writingTwoReportsGetsTheSecondANumericSuffixInsteadOfOverwritingTheFirst(@TempDir final Path root) {
        final Path prepDir = root.resolve("logs/cull-prep/2019-06");
        final DisasterDrawer drawer = drawer();

        final Path first = drawer.write(prepDir, "troubleshoot-report", "first");
        final Path second = drawer.write(prepDir, "troubleshoot-report", "second");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void sweepExpiredDeletesOnlyDrawerEntriesOlderThanThirtyDays(@TempDir final Path root) throws IOException {
        final Path cullPrepRoot = root.resolve("logs/cull-prep");
        final Path drawer1 = cullPrepRoot.resolve("2019-06/disasters");
        final Path oldEntry = writeFile(drawer1.resolve("2019-01-01_00-00-00-move-records-log.log"), "old");
        final Path freshEntry = writeFile(drawer1.resolve(recentStampedName()), "fresh");
        // A sibling non-drawer file at the same nesting depth, to prove the sweep doesn't wander
        // outside disasters/ folders.
        final Path unrelated = writeFile(cullPrepRoot.resolve("2019-06/index.json"), "{}");

        final int deleted = drawer().sweepExpired(cullPrepRoot);

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(oldEntry)).isFalse();
        assertThat(Files.exists(freshEntry)).isTrue();
        assertThat(Files.exists(unrelated)).isTrue();
    }

    @Test
    void sweepExpiredLeavesAnUnparseableFilenameAloneRatherThanGuessing(@TempDir final Path root) throws IOException {
        final Path cullPrepRoot = root.resolve("logs/cull-prep");
        final Path unparseable = writeFile(cullPrepRoot.resolve("2019-06/disasters/not-a-timestamped-name.log"), "?");

        final int deleted = drawer().sweepExpired(cullPrepRoot);

        assertThat(deleted).isZero();
        assertThat(Files.exists(unparseable)).isTrue();
    }

    @Test
    void sweepExpiredOnAMissingRootReturnsZeroWithoutThrowing(@TempDir final Path root) {
        final int deleted = drawer().sweepExpired(root.resolve("logs/cull-prep"));

        assertThat(deleted).isZero();
    }

    @Test
    void sweepExpiredGraveyardDeletesOnlyGraveyardFoldersOlderThanThirtyDays(@TempDir final Path root) throws IOException {
        final Path graveyardRoot = root.resolve("logs/disasters");
        final Path oldGraveyard = graveyardRoot.resolve("scope1-2019-01-01_00-00-00");
        writeFile(oldGraveyard.resolve("index.json"), "{}");
        // Preserves a nested disasters/ subfolder's own structure - proves the whole tree is swept,
        // not just the graveyard's top-level files.
        writeFile(oldGraveyard.resolve("disasters/2019-01-01_00-00-01-corrupt-original.json"), "?");
        final Path freshGraveyard = graveyardRoot.resolve(recentStampedGraveyardName());
        final Path freshEntry = writeFile(freshGraveyard.resolve("index.json"), "{}");

        final int deleted = drawer().sweepExpiredGraveyard(graveyardRoot);

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(oldGraveyard)).isFalse();
        assertThat(Files.exists(freshEntry)).isTrue();
    }

    @Test
    void sweepExpiredGraveyardParsesTheTimestampEvenWhenTheScopeTagItselfContainsHyphens(@TempDir final Path root)
            throws IOException {
        // A Year scope narrowed to specific months tags itself "2020-06-07-08" (CullScope.tag()) -
        // exactly the shape TIMESTAMP_SUFFIX's trailing anchor exists to parse correctly regardless
        // of how many hyphens the scope segment itself contributes.
        final Path oldGraveyard = root.resolve("logs/disasters/2020-06-07-08-2019-01-01_00-00-00");
        writeFile(oldGraveyard.resolve("index.json"), "{}");

        final int deleted = drawer().sweepExpiredGraveyard(root.resolve("logs/disasters"));

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(oldGraveyard)).isFalse();
    }

    @Test
    void sweepExpiredGraveyardLeavesAnUnparseableFolderNameAloneRatherThanGuessing(@TempDir final Path root) throws IOException {
        final Path graveyardRoot = root.resolve("logs/disasters");
        final Path unparseable = writeFile(graveyardRoot.resolve("not-a-timestamped-name/index.json"), "{}");

        final int deleted = drawer().sweepExpiredGraveyard(graveyardRoot);

        assertThat(deleted).isZero();
        assertThat(Files.exists(unparseable)).isTrue();
    }

    @Test
    void sweepExpiredGraveyardOnAMissingRootReturnsZeroWithoutThrowing(@TempDir final Path root) {
        final int deleted = drawer().sweepExpiredGraveyard(root.resolve("logs/disasters"));

        assertThat(deleted).isZero();
    }

    // A timestamp comfortably inside the 30-day retention window, so this entry must survive a sweep.
    private static String recentStampedName() {
        final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);
        return format.format(Instant.now().minus(Duration.ofDays(1))) + "-move-records-log.log";
    }

    // A graveyard folder name (<scope>-<timestamp>) comfortably inside the retention window.
    private static String recentStampedGraveyardName() {
        final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);
        return "scope1-" + format.format(Instant.now().minus(Duration.ofDays(1)));
    }

    private static Path writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static DisasterDrawer drawer() {
        return new DisasterDrawer(new NioMediaStore());
    }
}
