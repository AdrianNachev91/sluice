package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.Finding.InvalidCategory;
import photos.sluice.domain.cull.Finding.MissingSource;
import photos.sluice.domain.cull.Finding.StrayShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.job.WatchMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Fixture-writing helpers below mirror ApplyEngineTest's own - PrepDirDoctor reuses ApplyEngine's
// validate()/checkMissingSources() internally, so the same shard/sidecar/index fixtures apply.
class PrepDirDoctorTest {

    @Test
    void anAppliedRunReportsComplete(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.writeString(prepDir.resolve("decisions.json"), "{}");

        PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.COMPLETE);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void aMontageWithNoShardYetReportsWaitingWithoutAMissingShardFinding(@TempDir Path root) throws IOException {
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

        PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.WAITING);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void everyMontageShardedAndCleanReportsReady(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.READY);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void anOffContractDecisionReportsBlockedWithANoneRemedyFinding(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "meme", "not a configured category"));

        PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(
                new InvalidCategory("montage-001", 1, "meme", "allowed: junk, scenery, food, funny"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.NONE);
    }

    @Test
    void aStrayShardReportsBlockedWithAnAutoRemedyFinding(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        writeShard(prepDir, "montage-002"); // no montage-002 entry in index.json - a stray shard

        PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(new StrayShard("decisions-002.json"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.AUTO);
    }

    @Test
    void aMissingSourceWithNoMoveRecordReportsBlockedWithAChoiceRemedyFinding(@TempDir Path root) throws IOException {
        Path prepDir = prepDir(root);
        Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk, no move record
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).hasSize(1);
        assertThat(health.findings().getFirst()).isInstanceOf(MissingSource.class);
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.CHOICE);
        assertThat(health.findings().getFirst().describe()).contains(photo.toString());
    }

    @Test
    void findingsAreOrderedAutoRemedyBeforeNoneRemedy(@TempDir Path root) throws IOException {
        // A stray shard (AUTO) and an off-contract decision (NONE) are both shard-contract findings,
        // so both surface together. A MissingSource (CHOICE) is different - it only ever surfaces
        // once the shard contract is already clean (see PrepDirDoctor.diagnose()'s own doc for why).
        Path prepDir = prepDir(root);
        Path offContract = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(offContract, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(offContract));
        writeShard(prepDir, "montage-001", classificationJson(offContract, "meme", "not a configured category"));
        writeShard(prepDir, "montage-002"); // stray shard, AUTO remedy

        PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).extracting(Finding::remedy)
                .containsExactly(Finding.Remedy.AUTO, Finding.Remedy.NONE);
    }

    @Test
    void aShardContractProblemSuppressesMissingSourceCheckingForAnUnrelatedDecision(@TempDir Path root) throws IOException {
        // Guards against a misleading double finding. An off-contract decision already reports
        // InvalidCategory. An unrelated decision in the same batch, whose file is genuinely missing,
        // must not ALSO surface a MissingSource - the whole batch is already blocked on the shard
        // contract, the same gate apply() itself enforces before ever checking file existence.
        Path prepDir = prepDir(root);
        Path offContract = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path missingSource = root.resolve("Sorted/Photos/2019/06/b.jpg"); // never written, no move record
        writeFile(offContract, "x");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(offContract), sidecarEntry(missingSource));
        writeShard(prepDir, "montage-001",
                classificationJson(offContract, "meme", "not a configured category"),
                classificationJson(missingSource, "junk", "blurry"));

        PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(
                new InvalidCategory("montage-001", 1, "meme", "allowed: junk, scenery, food, funny"));
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

    private static PrepDirDoctor doctor(Path root) {
        Path libraryRoot = root.resolve("Library");
        var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), libraryRoot.toString(), root.resolve("Inbox").toString()));
        var mediaStore = new NioMediaStore();
        var cullPrepPort = new JsonCullPrepStore();
        var settings = fixedSettings();
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        var applyEngine = new ApplyEngine(pathsConfig, mediaStore, cullPrepPort, settings, new Sha256Hasher(), hashIndex,
                new DisasterDrawer(mediaStore));
        return new PrepDirDoctor(cullPrepPort, mediaStore, settings, applyEngine);
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
