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

    @Test
    void waitsForAPersonRatherThanCallingAModel() {
        assertThat(this.culler.type()).isEqualTo(ProviderType.MANUAL);
    }

    @Test
    void reportsEveryMontageCulledWhenEachHasAShard(@TempDir final Path dir) throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));
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

    @Test
    void countsAnUnparseableShardAsPresentRatherThanReportingItAsAProblem(@TempDir final Path dir) throws IOException,
            CullException {
        Files.writeString(dir.resolve("decisions-001.json"), "{ not json");

        assertThat(this.culler.cull(prep(dir, "montage-001"), options()))
                .isEqualTo(report(1, 0));
    }

    @Test
    void leavesAShardWithNoMatchingMontageToApplysGate(@TempDir final Path dir) throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a receipt"))));
        this.codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThat(this.culler.cull(prep(dir, "montage-001"), options()))
                .isEqualTo(report(1, 0));
    }

    // A sidecar is not an input here at all. The fixture is the resume case that reaches this
    // check: one montage holding a shard beside a damaged sidecar, another still without a shard.
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
    void progressCallbackLeavesOutTheMontageTheAgentNeverJudged(@TempDir final Path dir)
            throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));

        final List<String> ticks = new ArrayList<>();
        final CullReport cullReport = this.culler.cull(prep(dir, "montage-001", "montage-002"), allowPartial(),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(cullReport).isEqualTo(report(1, 1));
        assertThat(ticks).containsExactly("1/2");
    }

    @Test
    void aShardlessMontageInTheMiddleDoesNotBreakTheCountThatFollowsIt(@TempDir final Path dir)
            throws CullException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));
        this.codec.write(dir.resolve("decisions-003.json"), new DecisionShard("montage-003",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));

        final List<String> ticks = new ArrayList<>();
        this.culler.cull(prep(dir, "montage-001", "montage-002", "montage-003"), allowPartial(),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(ticks).containsExactly("1/3", "2/3");
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
