package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.util.List;

/**
 * Computes a waiting cull job's present/valid shard counts from its prep dir, and answers whether
 * that prep dir is worth resuming yet. Not a Spring bean. {@link CullEngine} owns the one instance
 * it needs, built from the collaborators it already receives.
 *
 * <p>Neither answer is a verdict on shard content. {@link ApplyPlanner#validate} is the only thing
 * that judges that, and it does so once, at the apply itself. The tally here is a display number,
 * computed one montage at a time so a card can name which montage is holding a run up. Readiness
 * asks a narrower question still: has everything arrived?
 *
 * <p>Every read here degrades rather than throws. A transiently unreadable index, sidecar, or shard
 * reports as not-yet-ready or not-yet-valid, never as an exception escaping to a caller. {@link Error}
 * stays uncaught. This class states that contract itself rather than depending on a caller's own
 * catch-all to hold it, since a display number and a watcher's poll should never be able to take a
 * caller down over a read that will very likely succeed on the next pass.
 */
final class ShardTallyCalculator {

    private static final Logger log = LoggerFactory.getLogger(ShardTallyCalculator.class);

    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final ApplyPlanner applyPlanner;
    private final LedgerReader ledgerReader;
    private final ShardValidator shardValidator = new ShardValidator();

    /**
     * Creates a calculator backed by the given prep-dir reader, cull settings, and apply gate.
     *
     * @param cullPrepPort {@link CullPrepPort} reads prep-dir index, sidecars, and shards
     * @param cullSettings {@link CullSettings} the configured cull categories
     * @param applyPlanner {@link ApplyPlanner} the single validator readiness is decided by
     * @param ledgerReader {@link LedgerReader} takes the disposition-ledger snapshot that validator honours
     */
    ShardTallyCalculator(final CullPrepPort cullPrepPort, final CullSettings cullSettings,
                         final ApplyPlanner applyPlanner, final LedgerReader ledgerReader) {
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
        this.applyPlanner = applyPlanner;
        this.ledgerReader = ledgerReader;
    }

    /**
     * present/valid computed per montage, one shard at a time, rather than through ApplyPlanner's
     * own whole-batch validate(). A cross-shard problem (a near-dup group id reused across two
     * montages, a file claimed by two different shards) isn't caught here. That montage still
     * counts as valid.
     *
     * <p>Acceptable because this number is only ever shown, never acted on. isReadyToResume() below
     * is what decides whether anything happens.
     *
     * <p>One ledger-resolved answer does reach it. A file the user resolved with TRUST_DECISION is
     * no longer treated as unreviewable, so the montage claiming it stops reading invalid. A montage
     * resolved with APPLY_ANYWAY still displays as invalid, since only the whole-batch pass knows to
     * trust its shard as its own scope. Neither changes what actually happens to the run.
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
        final List<Path> unreviewable = this.resolvedUnreviewable(prep);

        final List<MontageShardStatus> statuses = prep.entries().stream()
                .map(montage -> this.montageShardStatus(prep, montage, sidecarSrcs, categories, unreviewable))
                .toList();
        final int present = (int) statuses.stream().filter(MontageShardStatus::present).count();
        final int valid = (int) statuses.stream().filter(MontageShardStatus::valid).count();
        return new ShardTally(present, valid, prep.entries().size());
    }

    /**
     * Whether anything more is still arriving for prepDir, which is the only question a
     * {@link CullWatcher} needs answered. Every montage has a shard, and every one of those shards
     * parses. The culling agent has then said everything it is going to say, so the run is worth a
     * resume attempt.
     *
     * <p>Whether those shards are any good is not asked here, deliberately. Judging that would mean
     * a second validator alongside apply's own gate. This one reads raw disk state, where a user's
     * answer to a finding still looks like the finding. Worse, a problem it could see would be a
     * problem it never fires on. The run would poll on unresolved forever instead of resuming once
     * and settling on Blocked, where the findings are actually in front of somebody.
     *
     * <p>Parsing is the one content check, and it is here to tell a finished shard from a shard
     * being written right now. A file exists from the moment the agent opens it. Without this check
     * a poll landing mid-write would fire on a truncated shard and block the run over nothing.
     *
     * <p>Deliberately not the whole resume path. This stays read-only and claims no job slot, so a
     * poll costs nothing when the answer is no. A transiently unreadable index (mid-write by a
     * concurrent process) degrades to "not ready yet" rather than propagating - the same tolerance
     * readWaitingJob() already gives this case.
     *
     * @param prepDir {@link Path} the prep dir to check
     * @return boolean true if every montage has a shard and every shard parses
     */
    boolean isReadyToResume(final Path prepDir) {
        try {
            final PrepDir prep = this.cullPrepPort.readIndex(prepDir);
            return prep.entries().stream().allMatch(montage -> this.shardIsFinished(prep, montage));
        } catch (final RuntimeException e) {
            log.warn("Could not check readiness of {}, reporting it as not ready", prepDir, e);
            return false;
        }
    }

    /**
     * Whether one montage's shard is on disk and readable end to end.
     *
     * @param prep {@link PrepDir} the prep dir being checked
     * @param montage {@link String} the montage id to check
     * @return boolean true if the shard exists and parses
     */
    private boolean shardIsFinished(final PrepDir prep, final String montage) {
        try {
            if (!this.cullPrepPort.hasShard(prep.prepDir(), montage)) {
                return false;
            }
            this.cullPrepPort.readShard(prep.prepDir(), montage);
            return true;
        } catch (final RuntimeException e) {
            log.warn("Could not check {}'s shard in {}, reporting it as not finished", montage, prep.prepDir(), e);
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
        } catch (final RuntimeException e) {
            log.warn("Could not read {}'s sidecar in {}, contributing no files from it", montage, prep.prepDir(), e);
            return List.of();
        }
    }

    /**
     * prepDir's unreviewable list, ledger-resolved. A transiently unreadable ledger degrades to the
     * raw, unresolved list here, rather than failing the whole tally. Worst case a montage the user
     * already resolved briefly displays as invalid again; the next successful read corrects it.
     *
     * @param prep {@link PrepDir} the prep dir being tallied
     * @return a {@link List} of {@link Path} prepDir's unreviewable files, ledger-resolved if the ledger could be read
     */
    private List<Path> resolvedUnreviewable(final PrepDir prep) {
        try {
            return this.applyPlanner.resolvedUnreviewable(prep, this.ledgerReader.read(prep.prepDir()));
        } catch (final RuntimeException e) {
            log.warn("Could not read the ledger for {}, using its unresolved unreviewable list", prep.prepDir(), e);
            return prep.unreviewable();
        }
    }

    /**
     * Resolves a single montage's shard presence and validity.
     *
     * @param prep {@link PrepDir} the prep dir being tallied
     * @param montage {@link String} the montage id to check
     * @param sidecarSrcs a {@link List} of {@link Path} source paths of every in-scope sidecar entry
     * @param categories a {@link List} of {@link String} the configured cull category names
     * @param unreviewable a {@link List} of {@link Path} the ledger-resolved unreviewable files
     * @return {@link MontageShardStatus} the montage's presence and validity
     */
    private MontageShardStatus montageShardStatus(final PrepDir prep, final String montage,
                                                  final List<Path> sidecarSrcs,
                                                  final List<String> categories,
                                                  final List<Path> unreviewable) {
        try {
            if (!this.cullPrepPort.hasShard(prep.prepDir(), montage)) {
                return new MontageShardStatus(false, false);
            }
            final var shardFile = new ShardFile(montage, this.cullPrepPort.readShard(prep.prepDir(), montage));
            final var report = this.shardValidator.validate(List.of(shardFile), sidecarSrcs, categories, unreviewable);
            return new MontageShardStatus(true, report.valid());
        } catch (final RuntimeException e) {
            // Present but unparseable, or its own presence could not even be confirmed - either way
            // not valid, and never reported as absent, since an unconfirmed shard is not a missing one.
            log.warn("Could not check {}'s shard status in {}, reporting it as invalid", montage, prep.prepDir(), e);
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
