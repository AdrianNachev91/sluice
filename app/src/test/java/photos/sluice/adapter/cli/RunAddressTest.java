package photos.sluice.adapter.cli;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The root is a real temporary folder rather than a written-out path. An address is read as a path
// only when it is absolute, and which strings are absolute differs by operating system.
class RunAddressTest {

    private static final Instant WRITTEN = Instant.parse("2026-08-20T10:15:30Z");

    @TempDir
    private Path prepRoot;

    private final Pipeline pipeline = mock(Pipeline.class);
    private final RunAddress addresses = new RunAddress(this.pipeline);

    @Test
    void aScopeTagNamesTheFolderTheSiftCarryingItLivesIn() {
        when(this.pipeline.cullRuns()).thenReturn(this.listed("2019", "2019-06"));

        assertThat(this.addresses.folderFor("2019-06")).isEqualTo(this.prepRoot.resolve("2019-06"));
    }

    @Test
    void aCountTaggedSiftIsAddressedTheSameWayAYearScopedOneIs() {
        when(this.pipeline.cullRuns()).thenReturn(this.listed("oldest-30"));

        assertThat(this.addresses.folderFor("oldest-30")).isEqualTo(this.prepRoot.resolve("oldest-30"));
    }

    @Test
    void aFullPathIsTakenAsGivenWithoutAskingWhatSiftsThereAre() {
        final Path folder = this.prepRoot.resolve("2019-06");

        assertThat(this.addresses.folderFor(folder.toString())).isEqualTo(folder);
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void aPathIsTakenEvenWhereNoSiftOfThatNameWasEverStarted() {
        final Path folder = this.prepRoot.resolve("never-sifted");

        assertThat(this.addresses.folderFor(folder.toString())).isEqualTo(folder);
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void aTagNamingNoSiftIsRefusedWithTheOnesThatDoExistNamed() {
        when(this.pipeline.cullRuns()).thenReturn(this.listed("2019", "oldest-30"));

        final Refusal refusal = refusalOf(() -> this.addresses.folderFor("2020"));

        assertThat(refusal.kind()).isEqualTo(RefusalKind.RUN_NOT_FOUND);
        assertThat(refusal.sentence()).contains("2020").contains("2019").contains("oldest-30");
        assertThat(refusal.detail()).containsEntry("address", "2020").containsEntry("known",
                List.of("2019", "oldest-30"));
    }

    @Test
    void anInstallWithNoSiftsSaysSoRatherThanListingNone() {
        when(this.pipeline.cullRuns()).thenReturn(this.listed());

        assertThat(refusalOf(() -> this.addresses.folderFor("2019")).sentence())
                .isEqualTo("Sluice can't find a sift called 2019. It has none yet.");
    }

    @Test
    void aFolderNobodyCouldReadIsRefusedAsUnreadableRatherThanAsAnUnknownSift() {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Unlistable(this.prepRoot));

        final Refusal refusal = refusalOf(() -> this.addresses.folderFor("2019"));

        assertThat(refusal.kind()).isEqualTo(RefusalKind.RUNS_UNREADABLE);
        assertThat(refusal.sentence()).contains(this.prepRoot.toString());
    }

    @Test
    void noTagTheDomainBuildsCanBeMistakenForAPath() {
        assertThat(Path.of(CullScope.tag(new CullScope.Year(2019, null))).isAbsolute()).isFalse();
        assertThat(Path.of(CullScope.tag(new CullScope.Year(2019, List.of(6, 8, 11)))).isAbsolute()).isFalse();
        assertThat(Path.of(CullScope.tag(new CullScope.OldestN(30))).isAbsolute()).isFalse();
    }

    @Test
    void anAddressNoFileSystemCouldNameIsToldWhatSiftsThereAre() {
        when(this.pipeline.cullRuns()).thenReturn(this.listed("2019"));

        assertThat(refusalOf(() -> this.addresses.folderFor("2019" + Character.toString(0))).kind())
                .isEqualTo(RefusalKind.RUN_NOT_FOUND);
    }

    @Test
    void somethingThatIsNeitherATagNorAFullPathIsToldWhatSiftsThereAre() {
        when(this.pipeline.cullRuns()).thenReturn(this.listed("2019"));

        assertThat(refusalOf(() -> this.addresses.folderFor("logs/sift-prep/2019")).kind())
                .isEqualTo(RefusalKind.RUN_NOT_FOUND);
    }

    private CullRuns listed(final String... tags) {
        return new CullRuns.Listed(Stream.of(tags)
                .map(tag -> new CullRunSummary(tag, this.prepRoot.resolve(tag),
                        new PrepDirHealth(PrepDirHealth.State.READY, List.of()),
                        new ShardTally(25, 25, 25), WRITTEN))
                .toList());
    }

    private static Refusal refusalOf(final ThrowingCallable call) {
        final Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(ScopeRefusedException.class);
        return ((ScopeRefusedException) thrown).refusal();
    }
}
