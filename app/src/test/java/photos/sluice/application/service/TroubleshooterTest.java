package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.domain.cull.Finding.CorruptIndex;
import photos.sluice.domain.cull.Finding.MissingSource;
import photos.sluice.domain.cull.Finding.StrayShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.TroubleshootReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Fixture-writing helpers below mirror ApplyPlannerTest's and PrepDirDoctorTest's own.
// Troubleshooter reuses both PrepDirDoctor.diagnose() and ReconcileEngine.reconcile() internally,
// so the same shard/sidecar/index fixtures apply.
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
    void aCorruptIndexIsAutoRebuiltFromSidecarsAndTheRunProceedsToReady(@TempDir Path root) throws IOException, ApplyException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.writeString(prepDir.resolve("index.json"), "not valid json");

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.before().state()).isEqualTo(State.BLOCKED);
        assertThat(report.before().findings()).containsExactly(new CorruptIndex(prepDir.resolve("index.json")));
        assertThat(report.indexRebuilt()).isTrue();
        assertThat(report.after().state()).isEqualTo(State.READY);
        assertThat(report.after().findings()).isEmpty();
    }

    @Test
    void aCorruptIndexThatCannotBeRebuiltStaysBlockedWithIndexRebuiltFalse(@TempDir Path root) throws IOException, ApplyException {
        // The sole sidecar is itself unparseable - the rebuild guard has no ground truth to work
        // from, so it must refuse rather than write a silently-empty index.
        Path prepDir = prepDir(root);
        Files.writeString(prepDir.resolve("montage-001.json"), "not valid json");
        Files.writeString(prepDir.resolve("index.json"), "not valid json");

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.indexRebuilt()).isFalse();
        assertThat(report.after()).isEqualTo(report.before());
        assertThat(report.after().findings()).containsExactly(new CorruptIndex(prepDir.resolve("index.json")));
    }

    @Test
    void aStrayShardWithNoMontageActuallyUnclaimedIsLeftUnchanged(@TempDir Path root) throws IOException, ApplyException {
        // index.json declares only montage-001, which already has its own shard - so no montage is
        // unclaimed for the stray decisions-002.json to claim. autoRepairStrayShard()'s unambiguity
        // gate (exactly one montage currently missing a shard) never holds here. troubleshoot() must
        // therefore leave it exactly as diagnose() found it, rather than guessing at a repair.
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
        assertThat(report.strayShardsRepaired()).isEmpty();
        assertThat(report.after()).isEqualTo(report.before());
    }

    @Test
    void anUnambiguousStrayShardIsAutoRenamedIntoTheUnclaimedMontageAndTheRunGoesReady(@TempDir Path root)
            throws IOException, ApplyException {
        // montage-002 is declared but has no shard yet; a culler numbering slip wrote its decision
        // into decisions-003.json instead. Every file that shard names is a member of montage-002's
        // own sidecar, so the repair is unambiguous: it gets renamed into place and the run clears.
        Path prepDir = prepDir(root);
        Path claimed = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path misnamed = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(claimed, "x");
        writeFile(misnamed, "y");
        writeIndex(prepDir, 2, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(claimed));
        writeSidecar(prepDir, "montage-002", sidecarEntry(misnamed));
        writeShard(prepDir, "montage-001", classificationJson(claimed, "junk", "blurry"));
        // Written under decisions-003.json - no montage-003 entry exists, so this is the stray. Its
        // one decision names misnamed, a member of montage-002's own sidecar.
        Files.writeString(prepDir.resolve("decisions-003.json"),
                "{ \"montage\": \"montage-002\", \"decisions\": [ %s ] }"
                        .formatted(classificationJson(misnamed, "junk", "also blurry")));

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.before().findings()).containsExactly(new StrayShard("decisions-003.json"));
        assertThat(report.strayShardsRepaired()).containsExactly("decisions-003.json -> montage-002");
        assertThat(Files.exists(prepDir.resolve("decisions-003.json"))).isFalse();
        assertThat(Files.exists(prepDir.resolve("decisions-002.json"))).isTrue();
        assertThat(report.after().state()).isEqualTo(State.READY);
        assertThat(report.after().findings()).isEmpty();
    }

    @Test
    void aStrayShardNamingAFileOutsideTheCandidateMontagesSidecarIsLeftForAChoice(@TempDir Path root)
            throws IOException, ApplyException {
        // montage-002 is the only unclaimed montage, but the stray shard's decision names a file that
        // was never part of montage-002's own sidecar. That is not a genuine numbering slip, so AUTO
        // must refuse rather than guess. setAsideStrayShard() (or leaving it) is the CHOICE fallback.
        Path prepDir = prepDir(root);
        Path claimed = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path candidateOnly = root.resolve("Sorted/Photos/2019/06/b.jpg");
        Path unrelated = root.resolve("Sorted/Photos/2019/06/c.jpg");
        writeFile(claimed, "x");
        writeFile(candidateOnly, "y");
        writeFile(unrelated, "z");
        writeIndex(prepDir, 3, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(claimed), sidecarEntry(unrelated));
        writeSidecar(prepDir, "montage-002", sidecarEntry(candidateOnly));
        writeShard(prepDir, "montage-001", classificationJson(claimed, "junk", "blurry"));
        Files.writeString(prepDir.resolve("decisions-003.json"),
                "{ \"montage\": \"montage-002\", \"decisions\": [ %s ] }"
                        .formatted(classificationJson(unrelated, "junk", "wrong montage entirely")));

        TroubleshootReport report = troubleshooter(root).troubleshoot(prepDir);

        assertThat(report.strayShardsRepaired()).isEmpty();
        assertThat(Files.exists(prepDir.resolve("decisions-003.json"))).isTrue();
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
        return CullPrepTestSupport.troubleshooter(root, root.resolve("Library"));
    }
}
