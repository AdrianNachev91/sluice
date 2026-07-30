package photos.sluice.application.service;

import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.job.ShardTally;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Computes a waiting cull job's present/valid shard counts from its prep dir. Not a Spring bean.
 * {@link CullEngine} owns the one instance it needs, built from the same {@link CullPrepPort} and
 * {@link CullSettings} it already receives.
 */
final class ShardTallyCalculator {

    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final ShardValidator shardValidator = new ShardValidator();

    /**
     * Creates a calculator backed by the given prep-dir reader and cull settings.
     *
     * @param cullPrepPort {@link CullPrepPort} reads prep-dir index, sidecars, and shards
     * @param cullSettings {@link CullSettings} the configured cull categories
     */
    ShardTallyCalculator(final CullPrepPort cullPrepPort, final CullSettings cullSettings) {
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
    }

    /**
     * present/valid computed per montage, one shard at a time, rather than through ApplyPlanner's
     * own whole-batch validate(). A cross-shard problem (a near-dup group id reused across two
     * montages, a file claimed by two different shards) isn't caught here. That montage still
     * counts as valid.
     *
     * <p>That's an acceptable simplification for a progress-display number - the real gate stays
     * ApplyPlanner's full-batch validate(), unchanged by this tally.
     *
     * @param prep {@link PrepDir} the prep dir to tally
     * @return {@link ShardTally} present/valid/total shard counts
     */
    ShardTally tally(final PrepDir prep) {
        final List<Path> sidecarSrcs = prep.entries().stream()
                .flatMap(montage -> this.readSidecar(prep, montage).stream())
                .map(SidecarPhotoEntry::src)
                .toList();
        final List<String> categories = this.cullSettings.categories().stream().map(CullCategory::name).toList();

        final List<MontageShardStatus> statuses = prep.entries().stream()
                .map(montage -> this.montageShardStatus(prep, montage, sidecarSrcs, categories))
                .toList();
        final int present = (int) statuses.stream().filter(MontageShardStatus::present).count();
        final int valid = (int) statuses.stream().filter(MontageShardStatus::valid).count();
        return new ShardTally(present, valid, prep.entries().size());
    }

    /**
     * Cheap status check a CullWatcher polls repeatedly: does prepDir's tally already show every
     * montage present and valid? Deliberately not the heavier resume()/dispatchAndApply() path. A
     * transiently unreadable index (mid-write by a concurrent process) degrades to "not ready yet"
     * here rather than propagating - the same tolerance readWaitingJob() already gives this case.
     *
     * @param prepDir {@link Path} the prep dir to check
     * @return boolean true if every montage is present and valid
     */
    boolean isFullyValid(final Path prepDir) {
        try {
            final ShardTally shards = this.tally(this.cullPrepPort.readIndex(prepDir));
            return shards.valid() == shards.total();
        } catch (final UncheckedIOException e) {
            return false;
        }
    }

    /**
     * A sidecar this app wrote itself during prep should always be readable. A transiently unreadable
     * one (mid-write by a concurrent cull job) degrades to "contributes no in-scope files" here,
     * rather than failing the whole tally. That's the same tolerance montageShardStatus() already
     * gives an unparseable shard below.
     *
     * @param prep {@link PrepDir} the prep dir being tallied
     * @param montage {@link String} the montage id to read
     * @return a {@link List} of {@link SidecarPhotoEntry} the montage's sidecar entries, or empty if unreadable
     */
    private List<SidecarPhotoEntry> readSidecar(final PrepDir prep, final String montage) {
        try {
            return this.cullPrepPort.readSidecar(prep.prepDir(), montage);
        } catch (final UncheckedIOException e) {
            return List.of();
        }
    }

    /**
     * Resolves a single montage's shard presence and validity.
     *
     * @param prep {@link PrepDir} the prep dir being tallied
     * @param montage {@link String} the montage id to check
     * @param sidecarSrcs a {@link List} of {@link Path} source paths of every in-scope sidecar entry
     * @param categories a {@link List} of {@link String} the configured cull category names
     * @return {@link MontageShardStatus} the montage's presence and validity
     */
    private MontageShardStatus montageShardStatus(final PrepDir prep, final String montage,
                                                  final List<Path> sidecarSrcs,
                                                  final List<String> categories) {
        if (!this.cullPrepPort.hasShard(prep.prepDir(), montage)) {
            return new MontageShardStatus(false, false);
        }
        try {
            final var shardFile = new ShardFile(montage, this.cullPrepPort.readShard(prep.prepDir(), montage));
            final var report = this.shardValidator.validate(List.of(shardFile), sidecarSrcs, categories,
                    prep.unreviewable());
            return new MontageShardStatus(true, report.valid());
        } catch (final UncheckedIOException e) {
            // Present but unparseable, so not valid.
            return new MontageShardStatus(true, false);
        }
    }

    /**
     * One montage's shard status: whether its shard file exists at all, and whether it parses and
     * validates against the prep dir's sidecars and configured categories.
     */
    private record MontageShardStatus(boolean present, boolean valid) {
    }
}
