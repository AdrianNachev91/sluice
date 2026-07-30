package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.WatchMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalAgentCullerTest {

    private final ShardCodec codec = new ShardCodec();
    private final ExternalAgentCuller culler = this.culler();

    @Test
    void idIsExternalAgent() {
        assertThat(this.culler.id()).isEqualTo("external-agent");
    }

    @Test
    void reportsEveryMontageCulledWhenEachHasAValidShard(@TempDir final Path dir) throws IOException, CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        final Path keeper = dir.resolve("base").resolve("IMG_002.jpg");
        this.writeSidecar(dir, "montage-001", junk);
        this.writeSidecar(dir, "montage-002", keeper);
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));
        // An all-keeps montage still answers with a shard - an empty decisions list, not no file.
        this.codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThat(this.culler.cull(this.prep(dir, "montage-001", "montage-002"), options()))
                .isEqualTo(new CullReport(2, 0, 0, 0));
    }

    @Test
    void throwsNamingEveryMontageMissingItsShard(@TempDir final Path dir) throws IOException {
        this.writeSidecar(dir, "montage-001", dir.resolve("base").resolve("IMG_001.jpg"));
        this.writeSidecar(dir, "montage-002", dir.resolve("base").resolve("IMG_002.jpg"));

        assertThatThrownBy(() -> this.culler.cull(this.prep(dir, "montage-001", "montage-002"), options()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("montage-001: no shard decisions-001.json")
                .hasMessageContaining("montage-002: no shard decisions-002.json");
    }

    @Test
    void allowPartialWaivesMissingShardsAndReportsThemSkipped(@TempDir final Path dir) throws IOException, CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.writeSidecar(dir, "montage-001", junk);
        this.writeSidecar(dir, "montage-002", dir.resolve("base").resolve("IMG_002.jpg"));
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "blurry document"))));

        assertThat(this.culler.cull(this.prep(dir, "montage-001", "montage-002"), allowPartial()))
                .isEqualTo(new CullReport(1, 1, 0, 0));
    }

    @Test
    void allowPartialStillRejectsAnInvalidShard(@TempDir final Path dir) throws IOException {
        final Path photo = dir.resolve("base").resolve("IMG_001.jpg");
        this.writeSidecar(dir, "montage-001", photo);
        this.writeSidecar(dir, "montage-002", dir.resolve("base").resolve("IMG_002.jpg"));
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(photo, "blurry", "not a configured category"))));

        assertThatThrownBy(() -> this.culler.cull(this.prep(dir, "montage-001", "montage-002"), allowPartial()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("invalid action 'blurry'")
                .hasMessageNotContaining("no shard");
    }

    @Test
    void rejectsAShardWithNoMatchingMontage(@TempDir final Path dir) throws IOException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.writeSidecar(dir, "montage-001", junk);
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a receipt"))));
        this.codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThatThrownBy(() -> this.culler.cull(this.prep(dir, "montage-001"), options()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("decisions-002.json: no matching montage");
    }

    @Test
    void rejectsAStrayShardEvenWhenAllowPartialWaivesMissingOnes(@TempDir final Path dir) throws IOException {
        this.writeSidecar(dir, "montage-001", dir.resolve("base").resolve("IMG_001.jpg"));
        this.codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThatThrownBy(() -> this.culler.cull(this.prep(dir, "montage-001"), allowPartial()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("decisions-002.json: no matching montage")
                .hasMessageNotContaining("no shard decisions-001.json");
    }

    @Test
    void reportsAnUnparseableShardAmongTheRunsProblems(@TempDir final Path dir) throws IOException {
        this.writeSidecar(dir, "montage-001", dir.resolve("base").resolve("IMG_001.jpg"));
        this.writeSidecar(dir, "montage-002", dir.resolve("base").resolve("IMG_002.jpg"));
        Files.writeString(dir.resolve("decisions-001.json"), "{ not json");

        assertThatThrownBy(() -> this.culler.cull(this.prep(dir, "montage-001", "montage-002"), options()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("decisions-001.json: ")
                .hasMessageContaining("montage-002: no shard decisions-002.json");
    }

    @Test
    void aggregatesValidatorProblemsIntoTheThrow(@TempDir final Path dir) throws IOException {
        final Path photo = dir.resolve("base").resolve("IMG_001.jpg");
        this.writeSidecar(dir, "montage-001", photo);
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(dir.resolve("elsewhere").resolve("OTHER.jpg"), "junk", "meme"),
                        new Classification(photo, "junk", ""))));

        assertThatThrownBy(() -> this.culler.cull(this.prep(dir, "montage-001"), options()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("file out of scope")
                .hasMessageContaining("missing 'reason'");
    }

    @Test
    void healsADriftedPathThroughTheSidecarBasename(@TempDir final Path dir) throws IOException {
        final Path actual = dir.resolve("base").resolve("2019").resolve("06").resolve("IMG_001.jpg");
        this.writeSidecar(dir, "montage-001", actual);
        // The culler retyped the \YYYY\MM\ segment; the unique basename resolves it back into scope.
        final Path drifted = dir.resolve("base").resolve("2019").resolve("07").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(drifted, "junk", "photo of a screen"))));

        assertThatCode(() -> this.culler.cull(this.prep(dir, "montage-001"), options()))
                .doesNotThrowAnyException();
    }

    @Test
    void failsLoudWhenASidecarIsUnreadable(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("montage-001.json"), "{ not json");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001", List.of()));

        assertThatThrownBy(() -> this.culler.cull(this.prep(dir, "montage-001"), options()))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("montage-001.json");
    }

    @Test
    void returnsAnEmptyReportWhenThePrepDirHasNoMontages(@TempDir final Path dir) throws CullException {
        assertThat(this.culler.cull(this.prep(dir), options()))
                .isEqualTo(new CullReport(0, 0, 0, 0));
    }

    @Test
    void progressCallbackTicksOnceForEachMontageIncludingOneWithAMissingShard(@TempDir final Path dir)
            throws IOException, CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.writeSidecar(dir, "montage-001", junk);
        this.writeSidecar(dir, "montage-002", dir.resolve("base").resolve("IMG_002.jpg")); // no shard written
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));

        final List<String> ticks = new ArrayList<>();
        final CullReport report = this.culler.cull(this.prep(dir, "montage-001", "montage-002"), allowPartial(),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(report).isEqualTo(new CullReport(1, 1, 0, 0));
        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    private ExternalAgentCuller culler() {
        return new ExternalAgentCuller(new ShardCodec(), new SidecarReader(),
                new FixedSettings("external-agent", List.of(
                        new CullCategory("junk", "objectively worthless shots"),
                        new CullCategory("scenery", "unremarkable scenery"),
                        new CullCategory("food", "meal photos"),
                        new CullCategory("funny", "memes and funny screenshots"))));
    }

    private static CullOptions options() {
        return new CullOptions(false, null);
    }

    private static CullOptions allowPartial() {
        return new CullOptions(true, null);
    }

    private PrepDir prep(final Path prepDir, final String... entries) {
        return new PrepDir("2019-06", prepDir.resolve("base"), entries.length * 2, List.of(),
                entries.length, prepDir, List.of(entries));
    }

    // Writes a sidecar in the writer's on-disk shape, with the fields this adapter doesn't consume
    // present too, so reads are exercised against the real full document.
    private void writeSidecar(final Path prepDir, final String montage, final Path... srcs) throws IOException {
        final StringBuilder photos = new StringBuilder();
        for (final Path src : srcs) {
            if (!photos.isEmpty()) {
                photos.append(", ");
            }
            photos.append("""
                    { "src": "%s", "name": "%s", "time": "2019-06-20T15:00:10Z", "received": false }"""
                    .formatted(jsonEscaped(src), src.getFileName()));
        }
        Files.writeString(prepDir.resolve(montage + ".json"), """
                {
                  "montage": "%s",
                  "photos": [ %s ]
                }
                """.formatted(jsonEscaped(prepDir.resolve(montage + ".jpg")), photos));
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
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
