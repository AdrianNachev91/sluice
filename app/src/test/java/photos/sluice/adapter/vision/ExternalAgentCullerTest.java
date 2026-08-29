package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.PrepDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalAgentCullerTest {

    private final ShardCodec codec = new ShardCodec();
    private final ExternalAgentCuller culler = new ExternalAgentCuller();

    @Test
    void idIsExternalAgent() {
        assertThat(this.culler.describe().id()).isEqualTo("external-agent");
    }

    // What CullEngine reads to tell an ordinary pause from a failed run. Typed API instead, every
    // wait for a shard this provider exists to do would reach the user as a run that failed.
    @Test
    void waitsForAPersonRatherThanCallingAModel() {
        assertThat(this.culler.type()).isEqualTo(ProviderType.MANUAL);
    }

    @Test
    void reportsEveryMontageCulledWhenEachHasAShard(@TempDir final Path dir) throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));
        // This culler counts shards and never opens one, so an empty shard is as much an answer to
        // it as a full one. Whether the shard says anything usable is ShardValidator's question.
        this.codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThat(this.culler.cull(prep(dir, "montage-001", "montage-002"), options()))
                .isEqualTo(report(2, 0));
    }

    @Test
    void throwsNamingEveryMontageMissingItsShard(@TempDir final Path dir) {
        assertThatThrownBy(() -> this.culler.cull(prep(dir, "montage-001", "montage-002"), options()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("montage-001: no shard decisions-001.json")
                .hasMessageContaining("montage-002: no shard decisions-002.json");
    }

    @Test
    void allowPartialWaivesMissingShardsAndReportsThemSkipped(@TempDir final Path dir) throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "blurry document"))));

        assertThat(this.culler.cull(prep(dir, "montage-001", "montage-002"), allowPartial()))
                .isEqualTo(report(1, 1));
    }

    // Shard content is apply's gate to judge, so one the codec cannot parse still counts as present
    // here. Parsing it here as well would make the two disagree about the same file.
    @Test
    void countsAnUnparseableShardAsPresentRatherThanReportingItAsAProblem(@TempDir final Path dir) throws IOException,
            CullException {
        Files.writeString(dir.resolve("decisions-001.json"), "{ not json");

        assertThat(this.culler.cull(prep(dir, "montage-001"), options()))
                .isEqualTo(report(1, 0));
    }

    // Apply's gate reports a decisions file naming no current montage as a StrayShard finding, with
    // the user's own answer about it already applied. Raising it here too would re-ask a question
    // they may have settled, and this class cannot see their answer.
    @Test
    void leavesAShardWithNoMatchingMontageToApplysGate(@TempDir final Path dir) throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a receipt"))));
        this.codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThat(this.culler.cull(prep(dir, "montage-001"), options()))
                .isEqualTo(report(1, 0));
    }

    // Sidecar state is not an input to this class at all. It matters because a montage can hold a
    // shard whose sidecar is damaged while another still lacks one. A resume then genuinely runs
    // this check. Failing on the damaged sidecar would put the run out of reach of the
    // corrupt-sidecar answer the user gives at the apply phase.
    @Test
    void aDamagedSidecarNeverBlocksTheRun(@TempDir final Path dir) throws IOException, CullException {
        Files.writeString(dir.resolve("montage-001.json"), "{ not json");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001", List.of()));

        assertThat(this.culler.cull(prep(dir, "montage-001", "montage-002"), allowPartial()))
                .isEqualTo(report(1, 1));
    }

    @Test
    void returnsAnEmptyReportWhenThePrepDirHasNoMontages(@TempDir final Path dir) throws CullException {
        assertThat(this.culler.cull(prep(dir), options()))
                .isEqualTo(report(0, 0));
    }

    @Test
    void progressCallbackTicksOnceForEachMontageIncludingOneWithAMissingShard(@TempDir final Path dir)
            throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));

        final List<String> ticks = new ArrayList<>();
        final CullReport cullReport = this.culler.cull(prep(dir, "montage-001", "montage-002"), allowPartial(),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(cullReport).isEqualTo(report(1, 1));
        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    private static CullOptions options() {
        return CullOptions.unbounded(false);
    }

    private static CullOptions allowPartial() {
        return CullOptions.unbounded(true);
    }

    private static CullReport report(final int culled, final int skipped) {
        return new CullReport(culled, skipped, 0, TokenSpend.none("external-agent"), false);
    }

    private static PrepDir prep(final Path prepDir, final String... entries) {
        return new PrepDir("2019-06", List.of(CullCategory.of("junk", "objectively worthless")),
                prepDir.resolve("base"), entries.length * 2, List.of(), entries.length, prepDir, List.of(entries));
    }
}
