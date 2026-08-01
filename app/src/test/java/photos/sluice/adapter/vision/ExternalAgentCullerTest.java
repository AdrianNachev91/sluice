package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullReport;
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
        assertThat(this.culler.id()).isEqualTo("external-agent");
    }

    @Test
    void reportsEveryMontageCulledWhenEachHasAShard(@TempDir final Path dir) throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));
        // An all-keeps montage still answers with a shard - an empty decisions list, not no file.
        this.codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThat(this.culler.cull(prep(dir, "montage-001", "montage-002"), options()))
                .isEqualTo(new CullReport(2, 0, 0, 0));
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
                .isEqualTo(new CullReport(1, 1, 0, 0));
    }

    // Shard content is apply's gate to judge, so one the codec cannot parse still counts as present
    // here. Parsing it here as well would make the two disagree about the same file.
    @Test
    void countsAnUnparseableShardAsPresentRatherThanReportingItAsAProblem(@TempDir final Path dir) throws IOException,
            CullException {
        Files.writeString(dir.resolve("decisions-001.json"), "{ not json");

        assertThat(this.culler.cull(prep(dir, "montage-001"), options()))
                .isEqualTo(new CullReport(1, 0, 0, 0));
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
                .isEqualTo(new CullReport(1, 0, 0, 0));
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
                .isEqualTo(new CullReport(1, 1, 0, 0));
    }

    @Test
    void returnsAnEmptyReportWhenThePrepDirHasNoMontages(@TempDir final Path dir) throws CullException {
        assertThat(this.culler.cull(prep(dir), options()))
                .isEqualTo(new CullReport(0, 0, 0, 0));
    }

    @Test
    void progressCallbackTicksOnceForEachMontageIncludingOneWithAMissingShard(@TempDir final Path dir)
            throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));

        final List<String> ticks = new ArrayList<>();
        final CullReport report = this.culler.cull(prep(dir, "montage-001", "montage-002"), allowPartial(),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(report).isEqualTo(new CullReport(1, 1, 0, 0));
        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    private static CullOptions options() {
        return new CullOptions(false, null);
    }

    private static CullOptions allowPartial() {
        return new CullOptions(true, null);
    }

    private static PrepDir prep(final Path prepDir, final String... entries) {
        return new PrepDir("2019-06", prepDir.resolve("base"), entries.length * 2, List.of(),
                entries.length, prepDir, List.of(entries));
    }
}
