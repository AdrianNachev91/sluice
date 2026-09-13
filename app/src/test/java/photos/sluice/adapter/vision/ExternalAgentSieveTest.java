package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.SiftException;
import photos.sluice.application.port.out.SiftOptions;
import photos.sluice.application.port.out.SiftReport;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.domain.sift.SiftCategory;
import photos.sluice.domain.sift.Decision.Classification;
import photos.sluice.domain.sift.DecisionShard;
import photos.sluice.domain.sift.PrepDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalAgentSieveTest {

    private final ShardCodec codec = new ShardCodec();
    private final ExternalAgentSieve sieve = new ExternalAgentSieve();

    @Test
    void idIsExternalAgent() {
        assertThat(this.sieve.describe().id()).isEqualTo("external-agent");
    }

    @Test
    void waitsForAPersonRatherThanCallingAModel() {
        assertThat(this.sieve.type()).isEqualTo(ProviderType.MANUAL);
    }

    @Test
    void reportsEveryMontageSiftedWhenEachHasAShard(@TempDir final Path dir) throws SiftException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));
        this.codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThat(this.sieve.sift(prep(dir, "montage-001", "montage-002"), options()))
                .isEqualTo(report(2, 0));
    }

    @Test
    void throwsNamingEveryMontageMissingItsShard(@TempDir final Path dir) {
        assertThatThrownBy(() -> this.sieve.sift(prep(dir, "montage-001", "montage-002"), options()))
                .isInstanceOf(SiftException.class)
                .hasMessageContaining("montage-001: no shard decisions-001.json")
                .hasMessageContaining("montage-002: no shard decisions-002.json");
    }

    @Test
    void allowPartialWaivesMissingShardsAndReportsThemSkipped(@TempDir final Path dir) throws SiftException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "blurry document"))));

        assertThat(this.sieve.sift(prep(dir, "montage-001", "montage-002"), allowPartial()))
                .isEqualTo(report(1, 1));
    }

    @Test
    void countsAnUnparseableShardAsPresentRatherThanReportingItAsAProblem(@TempDir final Path dir) throws IOException,
            SiftException {
        Files.writeString(dir.resolve("decisions-001.json"), "{ not json");

        assertThat(this.sieve.sift(prep(dir, "montage-001"), options()))
                .isEqualTo(report(1, 0));
    }

    @Test
    void leavesAShardWithNoMatchingMontageToApplysGate(@TempDir final Path dir) throws SiftException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a receipt"))));
        this.codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThat(this.sieve.sift(prep(dir, "montage-001"), options()))
                .isEqualTo(report(1, 0));
    }

    // A sidecar is not an input here at all. The fixture is the resume case that reaches this
    // check: one montage holding a shard beside a damaged sidecar, another still without a shard.
    @Test
    void aDamagedSidecarNeverBlocksTheRun(@TempDir final Path dir) throws IOException, SiftException {
        Files.writeString(dir.resolve("montage-001.json"), "{ not json");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001", List.of()));

        assertThat(this.sieve.sift(prep(dir, "montage-001", "montage-002"), allowPartial()))
                .isEqualTo(report(1, 1));
    }

    @Test
    void returnsAnEmptyReportWhenThePrepDirHasNoMontages(@TempDir final Path dir) throws SiftException {
        assertThat(this.sieve.sift(prep(dir), options()))
                .isEqualTo(report(0, 0));
    }

    @Test
    void progressCallbackLeavesOutTheMontageTheAgentNeverJudged(@TempDir final Path dir)
            throws SiftException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));

        final List<String> ticks = new ArrayList<>();
        final SiftReport siftReport = this.sieve.sift(prep(dir, "montage-001", "montage-002"), allowPartial(),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(siftReport).isEqualTo(report(1, 1));
        assertThat(ticks).containsExactly("1/2");
    }

    @Test
    void aShardlessMontageInTheMiddleDoesNotBreakTheCountThatFollowsIt(@TempDir final Path dir)
            throws SiftException {
        final Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        this.codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));
        this.codec.write(dir.resolve("decisions-003.json"), new DecisionShard("montage-003",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));

        final List<String> ticks = new ArrayList<>();
        this.sieve.sift(prep(dir, "montage-001", "montage-002", "montage-003"), allowPartial(),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(ticks).containsExactly("1/3", "2/3");
    }

    private static SiftOptions options() {
        return SiftOptions.unbounded(false);
    }

    private static SiftOptions allowPartial() {
        return SiftOptions.unbounded(true);
    }

    private static SiftReport report(final int sifted, final int skipped) {
        return new SiftReport(sifted, skipped, 0, TokenSpend.none("external-agent"), false);
    }

    private static PrepDir prep(final Path prepDir, final String... entries) {
        return new PrepDir("2019-06", List.of(SiftCategory.of("junk", "objectively worthless")),
                prepDir.resolve("base"), entries.length * 2, List.of(), entries.length, prepDir, List.of(entries));
    }
}
