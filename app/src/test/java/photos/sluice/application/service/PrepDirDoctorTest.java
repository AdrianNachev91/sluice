package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.Finding.InvalidCategory;
import photos.sluice.domain.cull.Finding.MissingSource;
import photos.sluice.domain.cull.Finding.StrayShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Fixture-writing helpers below mirror ApplyPlannerTest's own. PrepDirDoctor reuses ApplyPlanner's
// validate()/checkMissingSources() internally, so the same shard/sidecar/index fixtures apply.
class PrepDirDoctorTest {

    @Test
    void anAppliedRunReportsComplete(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.writeString(prepDir.resolve("decisions.json"), "{}");

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.COMPLETE);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void aCompleteRunReportsCompleteEvenWhenIndexJsonIsCorrupt(@TempDir final Path root) throws IOException {
        // The completion check reads only decisions.json, since a COMPLETE run needs nothing else.
        // A corrupt index.json past that point must never surface as a blocking problem. It could
        // have been clobbered long after the run already finished.
        final Path prepDir = prepDir(root);
        Files.writeString(prepDir.resolve("index.json"), "not valid json");
        Files.writeString(prepDir.resolve("decisions.json"), "{}");

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.COMPLETE);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void aCorruptIndexReportsBlockedWithAnAutoRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        Files.writeString(prepDir.resolve("index.json"), "not valid json");

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(new Finding.CorruptIndex(prepDir.resolve("index.json")));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.AUTO);
    }

    @Test
    void aMissingIndexReportsBlockedWithAnAutoRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root); // index.json never written at all

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(new Finding.CorruptIndex(prepDir.resolve("index.json")));
    }

    @Test
    void aFailedIndexReadPropagatesRatherThanBeingDiagnosedAsCorrupt(@TempDir final Path root) throws IOException {
        // A directory where index.json is expected is a real read failure. Files.newInputStream
        // cannot open it. That is distinct from "not valid json" (malformed content) and from
        // "never written at all" (permanently absent, diagnosed the same as corrupt). This one must
        // propagate instead of becoming a false corruption diagnosis.
        final Path prepDir = prepDir(root);
        Files.createDirectory(prepDir.resolve("index.json"));

        assertThatThrownBy(() -> doctor().diagnose(prepDir))
                .isInstanceOf(UncheckedIOException.class)
                .isNotInstanceOf(MalformedPrepJsonException.class);
    }

    @Test
    void aCorruptSidecarForAMontageWithAShardReportsBlockedWithAChoiceRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        // No sidecar written for montage-001 at all - stands in for a missing or corrupt one.
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(new Finding.CorruptSidecar("montage-001"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.CHOICE);
    }

    // A shard file present but unparseable counts toward the tally as present, so the dir is past
    // WAITING and lands on the real gate. Diagnosis has to describe it rather than throw: this same
    // call drives a dashboard, where one damaged shard must not take the whole reading down.
    @Test
    void aCorruptShardReportsBlockedWithAnInformationalFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeFile(prepDir.resolve("decisions-001.json"), "{ not valid json");

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings())
                .containsExactly(new Finding.CorruptShard("montage-001", "decisions-001.json"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.NONE);
    }

    @Test
    void aCorruptSidecarForAMontageWithNoShardYetReportsWaitingWithoutAFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(culled, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        // montage-002 has no sidecar and no shard yet - still being culled, not yet actionable.

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.WAITING);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void aMontageWithNoShardYetReportsWaitingWithoutAMissingShardFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path uncalled = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(culled, "x");
        writeFile(uncalled, "y");
        writeIndex(prepDir, 2, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeSidecar(prepDir, "montage-002", sidecarEntry(uncalled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        // montage-002 has no shard yet - still being culled.

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.WAITING);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void everyMontageShardedAndCleanReportsReady(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.READY);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void anOffContractDecisionReportsBlockedWithANoneRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "meme", "not a configured category"));

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(
                new InvalidCategory("montage-001", 1, "meme", "allowed: junk, scenery, food, funny"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.NONE);
    }

    @Test
    void aStrayShardReportsBlockedWithAnAutoRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        writeShard(prepDir, "montage-002"); // no montage-002 entry in index.json - a stray shard

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(new StrayShard("decisions-002.json"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.AUTO);
    }

    @Test
    void aMissingSourceWithNoMoveRecordReportsBlockedWithAChoiceRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk, no move record
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).hasSize(1);
        assertThat(health.findings().getFirst()).isInstanceOf(MissingSource.class);
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.CHOICE);
        assertThat(health.findings().getFirst().describe()).contains(photo.toString());
    }

    @Test
    void findingsAreOrderedAutoRemedyBeforeNoneRemedy(@TempDir final Path root) throws IOException {
        // A stray shard (AUTO) and an off-contract decision (NONE) are both shard-contract findings,
        // so both surface together. A MissingSource (CHOICE) is different - it only ever surfaces
        // once the shard contract is already clean (see PrepDirDoctor.diagnose()'s own doc for why).
        final Path prepDir = prepDir(root);
        final Path offContract = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(offContract, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(offContract));
        writeShard(prepDir, "montage-001", classificationJson(offContract, "meme", "not a configured category"));
        writeShard(prepDir, "montage-002"); // stray shard, AUTO remedy

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).extracting(Finding::remedy)
                .containsExactly(Finding.Remedy.AUTO, Finding.Remedy.NONE);
    }

    @Test
    void aShardContractProblemSuppressesMissingSourceCheckingForAnUnrelatedDecision(@TempDir final Path root) throws IOException {
        // Guards against a misleading double finding. An off-contract decision already reports
        // InvalidCategory. An unrelated decision in the same batch, whose file is genuinely missing,
        // must not ALSO surface a MissingSource. The whole batch is already blocked on the shard
        // contract, the same gate apply() enforces before ever checking file existence.
        final Path prepDir = prepDir(root);
        final Path offContract = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path missingSource = root.resolve("Sorted/Photos/2019/06/b.jpg"); // never written, no move record
        writeFile(offContract, "x");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(offContract), sidecarEntry(missingSource));
        writeShard(prepDir, "montage-001",
                classificationJson(offContract, "meme", "not a configured category"),
                classificationJson(missingSource, "junk", "blurry"));

        final PrepDirHealth health = doctor().diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(
                new InvalidCategory("montage-001", 1, "meme", "allowed: junk, scenery, food, funny"));
    }

    @Test
    void purgeCompletedDeletesOnlyCompletedRunsAndReportsSkippedScopesWithTheirState(@TempDir final Path root)
            throws IOException {
        final Path complete = prepDir(root, "complete1");
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(complete, 1, List.of("montage-001"));
        writeSidecar(complete, "montage-001", sidecarEntry(photo));
        writeShard(complete, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.writeString(complete.resolve("decisions.json"), "{}");
        final Path waiting = prepDir(root, "waiting1");
        writeIndex(waiting, 1, List.of("montage-001")); // no shard yet - still culling

        final PurgeReport report = doctor().purgeCompleted(root.resolve("logs/cull-prep"));

        assertThat(report.purged()).containsExactly("complete1");
        assertThat(report.skipped()).containsExactly(entry("waiting1", State.WAITING));
        assertThat(Files.exists(complete)).isFalse();
        assertThat(Files.exists(waiting)).isTrue();
    }

    @Test
    void purgeCompletedOnAMissingCullPrepRootReturnsAnEmptyReport(@TempDir final Path root) {
        final PurgeReport report = doctor().purgeCompleted(root.resolve("logs/cull-prep"));

        assertThat(report.purged()).isEmpty();
        assertThat(report.skipped()).isEmpty();
    }

    private static Path prepDir(final Path root) throws IOException {
        return prepDir(root, "scope1");
    }

    private static Path prepDir(final Path root, final String scope) throws IOException {
        final Path dir = root.resolve("logs/cull-prep").resolve(scope);
        Files.createDirectories(dir);
        return dir;
    }

    private static void writeIndex(final Path prepDir, final int photos, final List<String> entries) {
        new PrepIndexWriter().write(prepDir.resolve("index.json"),
                new PrepDir("2019-06", prepDir.resolve("base"), photos, List.of(), entries.size(), prepDir, entries));
    }

    private static void writeSidecar(final Path prepDir, final String montage, final SidecarPhotoEntry... photos) {
        new SidecarWriter().write(prepDir.resolve(montage + ".json"), prepDir.resolve(montage + ".jpg"),
                List.of(photos));
    }

    private static SidecarPhotoEntry sidecarEntry(final Path src) {
        return new SidecarPhotoEntry(src, src.getFileName().toString(), Instant.parse("2019-06-15T10:00:00Z"), false);
    }

    private static void writeShard(final Path prepDir, final String montage, final String... decisionsJson) throws IOException {
        final String shardName = montage.replaceFirst("^montage-", "decisions-") + ".json";
        Files.writeString(prepDir.resolve(shardName),
                "{ \"montage\": \"%s\", \"decisions\": [ %s ] }".formatted(montage, String.join(", ", decisionsJson)));
    }

    private static String classificationJson(final Path file, final String category, final String reason) {
        return "{ \"file\": \"%s\", \"action\": \"%s\", \"reason\": \"%s\" }"
                .formatted(file.toString().replace("\\", "\\\\"), category, reason);
    }

    private static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static PrepDirDoctor doctor() {
        return CullPrepTestSupport.prepDirDoctor();
    }
}
