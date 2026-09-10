package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.MalformedPrepJsonException;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes a waiting cull job's present/valid shard counts from its prep dir, and answers whether
 * that prep dir is worth resuming yet.
 *
 * <p>Neither answer is a verdict on shard content. The tally is a display number, computed one
 * montage at a time. Readiness asks a narrower question still: has everything arrived?
 *
 * <p>Every read here degrades rather than throws. A transiently unreadable index, sidecar, or shard
 * reports as not-yet-ready or not-yet-valid, never as an exception escaping to a caller. {@link Error}
 * stays uncaught.
 */
final class ShardTallyCalculator {

    private static final Logger log = LoggerFactory.getLogger(ShardTallyCalculator.class);

    private final CullPrepPort cullPrepPort;
    private final ApplyPlanner applyPlanner;
    private final LedgerReader ledgerReader;
    private final ShardValidator shardValidator = new ShardValidator();

    // A watcher polls this every couple of seconds, so a stuck read writes its trace on every tick
    // for as long as the watch is armed. Each read keeps the first trace of a given cause and drops
    // the rest; the message still names the prep dir every time. Concurrent because the watcher
    // thread and a troubleshooting read can both be in here.
    private final Set<String> tracedCauses = ConcurrentHashMap.newKeySet();

    /**
     * Creates a calculator backed by the given prep-dir reader and apply gate.
     *
     * @param cullPrepPort {@link CullPrepPort} reads prep-dir index, sidecars, and shards
     * @param applyPlanner {@link ApplyPlanner} resolves the unreviewable list against the ledger
     * @param ledgerReader {@link LedgerReader} takes the disposition-ledger snapshot that validator honours
     */
    ShardTallyCalculator(final CullPrepPort cullPrepPort, final ApplyPlanner applyPlanner,
                         final LedgerReader ledgerReader) {
        this.cullPrepPort = cullPrepPort;
        this.applyPlanner = applyPlanner;
        this.ledgerReader = ledgerReader;
    }

    /**
     * present/valid computed per montage, one shard at a time. A cross-shard problem (a near-dup
     * group id reused across two montages, a file claimed by two different shards) isn't caught
     * here. That montage still counts as valid.
     *
     * <p>One ledger-resolved answer does reach it. A file the user resolved with TRUST_DECISION is
     * no longer treated as unreviewable, so the montage claiming it stops reading invalid. A montage
     * resolved with APPLY_ANYWAY still displays as invalid.
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
     * <p>Asking for the two separately would open every shard twice. They share every read: whether
     * a shard is there and parses is what readiness is, and it is also the first half of what the
     * tally counts.
     *
     * @param prepDir {@link Path} the prep dir to read
     * @return {@link Reading} whether it is worth resuming, and how far through its sheets it is
     */
    Reading poll(final Path prepDir) {
        try {
            return this.readingOf(this.cullPrepPort.readIndex(prepDir));
        } catch (final MalformedPrepJsonException e) {
            warnBriefly(notReady(prepDir), e);
            return new Reading(false, null);
        } catch (final RuntimeException e) {
            this.warnWithCause("poll", notReady(prepDir), e);
            return new Reading(false, null);
        }
    }

    /**
     * What one prep dir's own index says about it.
     *
     * <p>Readiness is that every sheet has an answer that parses. It promises nothing about whether
     * those answers are any good. This reads raw disk, where a user's answer to a finding still
     * looks like the finding.
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
                        srcsByMontage.get(montage), sidecarSrcs, unreviewable))
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
        } catch (final MalformedPrepJsonException e) {
            warnBriefly(noFilesFrom(prep, montage), e);
            return List.of();
        } catch (final RuntimeException e) {
            this.warnWithCause("readSidecar", noFilesFrom(prep, montage), e);
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
            this.warnWithCause("resolvedUnreviewable",
                    "Could not read the ledger for " + prep.prepDir()
                            + ", using its unresolved unreviewable list", e);
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
        } catch (final MalformedPrepJsonException e) {
            // Present but unparseable, so not valid. Never absent: the file is there.
            warnBriefly(invalidShard(prep, montage), e);
            return new MontageShardStatus(true, false, false);
        } catch (final RuntimeException e) {
            // Its own presence could not be confirmed. Still not absent, since an unconfirmed shard
            // is not a missing one.
            this.warnWithCause("montageShardStatus", invalidShard(prep, montage), e);
            return new MontageShardStatus(true, false, false);
        }
    }

    /**
     * Reports a degraded read whose cause the code expects, naming the cause and no more.
     *
     * @param what {@link String} what could not be read, and what this class did instead
     * @param failure {@link RuntimeException} what it threw
     */
    private static void warnBriefly(final String what, final RuntimeException failure) {
        log.warn("{}: {}", what, failure.toString());
    }

    /**
     * Reports a degraded read whose cause nothing here classifies, with the stack trace the first
     * time that read meets that cause.
     *
     * @param read {@link String} which read failed
     * @param what {@link String} what could not be read, and what this class did instead
     * @param failure {@link RuntimeException} what it threw
     */
    private void warnWithCause(final String read, final String what, final RuntimeException failure) {
        if (this.tracedCauses.add(read + ':' + failure.getClass().getName())) {
            log.warn("{}", what, failure);
        } else {
            warnBriefly(what, failure);
        }
    }

    /**
     * @param prepDir {@link Path} the prep dir that could not be read
     * @return {@link String} the line a failed index read reports
     */
    private static String notReady(final Path prepDir) {
        return "Could not read " + prepDir + ", reporting it as not ready";
    }

    /**
     * @param prep {@link PrepDir} the prep dir being tallied
     * @param montage {@link String} the montage whose sidecar could not be read
     * @return {@link String} the line a failed sidecar read reports
     */
    private static String noFilesFrom(final PrepDir prep, final String montage) {
        return "Could not read " + montage + "'s sidecar in " + prep.prepDir()
                + ", contributing no files from it";
    }

    /**
     * @param prep {@link PrepDir} the prep dir being tallied
     * @param montage {@link String} the montage whose shard could not be checked
     * @return {@link String} the line a failed shard check reports
     */
    private static String invalidShard(final PrepDir prep, final String montage) {
        return "Could not check " + montage + "'s shard status in " + prep.prepDir()
                + ", reporting it as invalid";
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
