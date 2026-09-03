package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes a waiting cull job's present/valid shard counts from its prep dir, and answers whether
 * that prep dir is worth resuming yet.
 *
 * <p>Neither answer is a verdict on shard content. {@link ApplyPlanner#validate} is the only thing
 * that judges that, and it does so once, at the apply itself. The tally here is a display number,
 * computed one montage at a time so a card can name which montage is holding a run up. Readiness
 * asks a narrower question still: has everything arrived?
 *
 * <p>Every read here degrades rather than throws. A transiently unreadable index, sidecar, or shard
 * reports as not-yet-ready or not-yet-valid, never as an exception escaping to a caller. {@link Error}
 * stays uncaught. This class states that contract itself rather than depending on a caller's own
 * catch-all to hold it. A display number and a watcher's poll should never be able to take a caller
 * down over a read that will very likely succeed on the next pass.
 */
final class ShardTallyCalculator {

    private static final Logger log = LoggerFactory.getLogger(ShardTallyCalculator.class);

    private final CullPrepPort cullPrepPort;
    private final ApplyPlanner applyPlanner;
    private final LedgerReader ledgerReader;
    private final ShardValidator shardValidator = new ShardValidator();

    /**
     * Creates a calculator backed by the given prep-dir reader and apply gate.
     *
     * @param cullPrepPort {@link CullPrepPort} reads prep-dir index, sidecars, and shards
     * @param applyPlanner {@link ApplyPlanner} the single validator readiness is decided by
     * @param ledgerReader {@link LedgerReader} takes the disposition-ledger snapshot that validator honours
     */
    ShardTallyCalculator(final CullPrepPort cullPrepPort, final ApplyPlanner applyPlanner,
                         final LedgerReader ledgerReader) {
        this.cullPrepPort = cullPrepPort;
        this.applyPlanner = applyPlanner;
        this.ledgerReader = ledgerReader;
    }

    /**
     * present/valid computed per montage, one shard at a time, rather than through ApplyPlanner's
     * own whole-batch validate(). A cross-shard problem (a near-dup group id reused across two
     * montages, a file claimed by two different shards) isn't caught here. That montage still
     * counts as valid.
     *
     * <p>Acceptable because this validity number is only ever shown.
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
        // Never null on this route. A reading only answers null where the index could not be read,
        // and this one is handed the index already read.
        return Objects.requireNonNull(this.readingOf(prep).tally());
    }

    /**
     * Both answers about one prep dir, from a single walk of it.
     *
     * <p>A watcher wants readiness and a card wants the tally, and asking separately would open
     * every shard twice per poll. The two share every read. Whether a shard is there and parses is
     * what readiness is, and it is also the first half of what the tally counts.
     *
     * @param prepDir {@link Path} the prep dir to read
     * @return {@link Reading} whether it is worth resuming, and how far through its sheets it is
     */
    Reading poll(final Path prepDir) {
        try {
            return this.readingOf(this.cullPrepPort.readIndex(prepDir));
        } catch (final RuntimeException e) {
            log.warn("Could not read {}, reporting it as not ready: {}", prepDir, e.toString());
            return new Reading(false, null);
        }
    }

    /**
     * What one prep dir's own index says about it.
     *
     * <p>Readiness is that every sheet has an answer that parses. It promises nothing about whether
     * those answers are any good, which is apply's own gate to decide. This reads raw disk, where a
     * user's answer to a finding still looks like the finding.
     *
     * <p>Parsing is the one content check, and it tells a finished shard from one being written
     * right now. A file exists from the moment the agent opens it.
     *
     * @param prep {@link PrepDir} the prep dir, already read
     * @return {@link Reading} whether it is worth resuming, and how far through its sheets it is
     */
    private Reading readingOf(final PrepDir prep) {
        // Held per montage as well as flattened. A verdict's file is judged against the whole run's
        // set; whether a shard covers its sheet is judged against that one sheet's.
        final Map<String, List<Path>> srcsByMontage = new LinkedHashMap<>();
        for (final String montage : prep.entries()) {
            srcsByMontage.put(montage, this.readSidecar(prep, montage).stream()
                    .map(SidecarPhotoEntry::src)
                    .toList());
        }
        final List<Path> sidecarSrcs = srcsByMontage.values().stream().flatMap(List::stream).toList();
        final List<Path> unreviewable = this.resolvedUnreviewable(prep);

        final List<MontageShardStatus> statuses = prep.entries().stream()
                .map(montage -> this.montageShardStatus(prep, montage,
                        srcsByMontage.getOrDefault(montage, List.of()), sidecarSrcs, unreviewable))
                .toList();
        final int present = (int) statuses.stream().filter(MontageShardStatus::present).count();
        final int valid = (int) statuses.stream().filter(MontageShardStatus::valid).count();
        return new Reading(statuses.stream().allMatch(MontageShardStatus::parsed),
                new ShardTally(present, valid, prep.entries().size()));
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
            log.warn("Could not read {}'s sidecar in {}, contributing no files from it: {}",
                    montage, prep.prepDir(), e.toString());
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
            log.warn("Could not read the ledger for {}, using its unresolved unreviewable list: {}",
                    prep.prepDir(), e.toString());
            return prep.unreviewable();
        }
    }

    /**
     * Resolves a single montage's shard presence and validity.
     *
     * @param prep {@link PrepDir} the prep dir being tallied
     * @param montage {@link String} the montage id to check
     * @param sheetSrcs a {@link List} of {@link Path} source paths this one montage's sidecar lists
     * @param sidecarSrcs a {@link List} of {@link Path} source paths of every in-scope sidecar entry
     * @param unreviewable a {@link List} of {@link Path} the ledger-resolved unreviewable files
     * @return {@link MontageShardStatus} the montage's presence and validity
     */
    private MontageShardStatus montageShardStatus(final PrepDir prep, final String montage,
                                                  final List<Path> sheetSrcs,
                                                  final List<Path> sidecarSrcs,
                                                  final List<Path> unreviewable) {
        try {
            if (!this.cullPrepPort.hasShard(prep.prepDir(), montage)) {
                return new MontageShardStatus(false, false, false);
            }
            final var shardFile = new ShardFile(montage, this.cullPrepPort.readShard(prep.prepDir(), montage),
                    sheetSrcs);
            final var report = this.shardValidator.validate(List.of(shardFile), sidecarSrcs, prep.categoryNames(),
                    unreviewable);
            return new MontageShardStatus(true, true, report.valid());
        } catch (final RuntimeException e) {
            // Present but unparseable, or its own presence could not even be confirmed. Either way
            // not valid, and never absent, since an unconfirmed shard is not a missing one.
            log.warn("Could not check {}'s shard status in {}, reporting it as invalid: {}",
                    montage, prep.prepDir(), e.toString());
            return new MontageShardStatus(true, false, false);
        }
    }

    /**
     * One pass over a prep dir, answering both questions asked of it.
     *
     * @param readyToResume boolean whether every sheet has an answer that could be read
     * @param tally {@link ShardTally} how far through its sheets it is, or null where the prep dir
     *     could not be read at all
     */
    record Reading(boolean readyToResume, @Nullable ShardTally tally) {
    }

    /**
     * One montage's shard status.
     *
     * <p>Parsing and validating are separate because a shard that parses has said everything it is
     * going to say, whatever it says. Nothing further arrives for it, so a run whose every shard
     * parses is one to attempt rather than one to keep waiting on.
     *
     * @param present boolean whether the shard file is there
     * @param parsed boolean whether it could be read into decisions
     * @param valid boolean whether those decisions hold up on their own
     */
    private record MontageShardStatus(boolean present, boolean parsed, boolean valid) {
    }
}
