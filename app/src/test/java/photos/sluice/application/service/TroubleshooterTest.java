package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.cull.Finding.MissingSource;
import photos.sluice.domain.cull.Finding.StrayShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.TroubleshootReport;
import photos.sluice.domain.job.WatchMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Fixture-writing helpers below mirror ApplyEngineTest's and PrepDirDoctorTest's own - Troubleshooter
// reuses both PrepDirDoctor.diagnose() and ApplyEngine.reconcile() internally, so the same
// shard/sidecar/index fixtures apply.
class TroubleshooterTest {

    @Test
    void aReadyPrepDirIsReportedUnchangedWithNoReconcileAttempted(@TempDir Path root) throws IOException, ApplyException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.before().state()).isEqualTo(State.READY);
        assertThat(report.reconcile()).isNull();
        assertThat(report.after()).isEqualTo(report.before());
    }

    @Test
    void aCompletePrepDirIsReportedUnchangedWithNoReconcileAttempted(@TempDir Path root) throws IOException, ApplyException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.writeString(prepDir.resolve("decisions.json"), "{}");

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.before().state()).isEqualTo(State.COMPLETE);
        assertThat(report.reconcile()).isNull();
        assertThat(report.after()).isEqualTo(report.before());
    }

    @Test
    void aWaitingPrepDirIsReportedUnchangedWithNoReconcileAttempted(@TempDir Path root) throws IOException, ApplyException {
        Path prepDir = prepDir(root);
        Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path uncalled = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(culled, "x");
        writeFile(uncalled, "y");
        writeIndex(prepDir, 2, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeSidecar(prepDir, "montage-002", sidecarEntry(uncalled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        // montage-002 has no shard yet - still being culled.

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.before().state()).isEqualTo(State.WAITING);
        assertThat(report.reconcile()).isNull();
        assertThat(report.after()).isEqualTo(report.before());
    }

    @Test
    void aStrayShardAloneIsLeftUnchangedSinceNoAutoRepairExistsYet(@TempDir Path root) throws IOException, ApplyException {
        // Renaming an unambiguous stray shard into place isn't built yet. Until it is, a stray-shard-
        // only diagnosis has no MissingSource finding to trigger reconcile(), so troubleshoot() must
        // leave it exactly as diagnose() found it rather than silently pretending to have handled it.
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        writeShard(prepDir, "montage-002"); // no montage-002 entry in index.json - a stray shard

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.before().state()).isEqualTo(State.BLOCKED);
        assertThat(report.before().findings()).containsExactly(new StrayShard("decisions-002.json"));
        assertThat(report.reconcile()).isNull();
        assertThat(report.after()).isEqualTo(report.before());
    }

    @Test
    void aMissingSourceFindingTriggersReconcileAndClearsOnceItRebuildsTheMissingRecord(@TempDir Path root)
            throws IOException, ApplyException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written - stands in for an already-moved file
        Path dest = root.resolve("Review/junk/a.jpg");
        writeFile(dest, "already-moved-content");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.before().state()).isEqualTo(State.BLOCKED);
        assertThat(report.before().findings()).containsExactly(
                new MissingSource(photo, prepDir.resolve("move-records.log")));
        assertThat(report.reconcile()).isNotNull();
        assertThat(report.reconcile().reconstructed()).isEqualTo(1);
        assertThat(report.after().state()).isEqualTo(State.READY);
        assertThat(report.after().findings()).isEmpty();
    }

    @Test
    void aMissingSourceThatReconcileCannotAccountForStaysBlockedAfterTroubleshooting(@TempDir Path root)
            throws IOException, ApplyException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/gone.jpg"); // never written, no destination candidate either
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.reconcile()).isNotNull();
        assertThat(report.reconcile().reconstructed()).isZero();
        assertThat(report.after().state()).isEqualTo(State.BLOCKED);
        assertThat(report.after().findings()).containsExactly(
                new MissingSource(photo, prepDir.resolve("move-records.log")));
    }

    @Test
    void troubleshootFilesTheRenderedReportIntoTheDisasterDrawer(@TempDir Path root) throws IOException, ApplyException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        troubleshooter(root).troubleshoot(prepDir);

        Path drawer = prepDir.resolve("disasters");
        assertThat(Files.exists(drawer)).isTrue();
        try (var entries = Files.list(drawer)) {
            List<Path> filed = entries.toList();
            assertThat(filed).hasSize(1);
            assertThat(filed.getFirst().getFileName().toString()).contains("troubleshoot-report").endsWith(".txt");
            assertThat(Files.readString(filed.getFirst())).contains("Before: READY").contains("After: READY");
        }
    }

    private static Path prepDir(Path root) throws IOException {
        Path dir = root.resolve("logs/cull-prep/scope1");
        Files.createDirectories(dir);
        return dir;
    }

    private static void writeIndex(Path prepDir, int photos, List<String> entries) {
        new PrepIndexWriter().write(prepDir.resolve("index.json"),
                new PrepDir("2019-06", prepDir.resolve("base"), photos, List.of(), entries.size(), prepDir, entries));
    }

    private static void writeSidecar(Path prepDir, String montage, SidecarPhotoEntry... photos) {
        new SidecarWriter().write(prepDir.resolve(montage + ".json"), prepDir.resolve(montage + ".jpg"), List.of(photos));
    }

    private static SidecarPhotoEntry sidecarEntry(Path src) {
        return new SidecarPhotoEntry(src, src.getFileName().toString(), Instant.parse("2019-06-15T10:00:00Z"), false);
    }

    private static void writeShard(Path prepDir, String montage, String... decisionsJson) throws IOException {
        String shardName = montage.replaceFirst("^montage-", "decisions-") + ".json";
        Files.writeString(prepDir.resolve(shardName),
                "{ \"montage\": \"%s\", \"decisions\": [ %s ] }".formatted(montage, String.join(", ", decisionsJson)));
    }

    private static String classificationJson(Path file, String category, String reason) {
        return "{ \"file\": \"%s\", \"action\": \"%s\", \"reason\": \"%s\" }"
                .formatted(file.toString().replace("\\", "\\\\"), category, reason);
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Troubleshooter troubleshooter(Path root) {
        Path libraryRoot = root.resolve("Library");
        var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), libraryRoot.toString(), root.resolve("Inbox").toString()));
        var mediaStore = new NioMediaStore();
        var cullPrepPort = new JsonCullPrepStore();
        var settings = fixedSettings();
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        var disasterDrawer = new DisasterDrawer(mediaStore);
        var applyEngine = new ApplyEngine(pathsConfig, mediaStore, cullPrepPort, settings, new Sha256Hasher(), hashIndex,
                disasterDrawer);
        var prepDirDoctor = new PrepDirDoctor(cullPrepPort, mediaStore, settings, applyEngine);
        return new Troubleshooter(prepDirDoctor, applyEngine, disasterDrawer);
    }

    private static CullSettings fixedSettings() {
        return new FixedSettings("external-agent", List.of(
                new CullCategory("junk", "junk description"),
                new CullCategory("scenery", "scenery description"),
                new CullCategory("food", "food description"),
                new CullCategory("funny", "funny description")));
    }

    private record FixedSettings(String provider, List<CullCategory> categories) implements CullSettings {

        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }

        @Override
        public ExternalAgentSettings externalAgent() {
            return new ExternalAgentSettings(WatchMode.MANUAL, null);
        }
    }
}
