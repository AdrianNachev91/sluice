package photos.sluice.adapter.vision;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.ProgressCallback;

import java.nio.file.Files;
import java.util.ArrayList;

/**
 * The {@link VisionCuller} provider for a user whose vision judgement comes from an agent outside
 * this app. That agent reads the montages and drops a {@code decisions-NNN.json} shard per montage
 * into the prep directory on its own schedule. Calling {@code cull()} is therefore a
 * single-attempt presence check, never a wait. It asks one question: which montages still have no
 * shard file at all. It throws {@link CullException} naming every montage that has none. The throw
 * is the poke - write the missing shards, run again.
 *
 * <p>Whether those shards are any good is deliberately not asked here. Apply's own gate is the
 * single validator of shard content. It is also the only one a run passes through once every
 * montage has a shard, since no culler is entered at all on that path.
 *
 * <p>One validator rather than two is what keeps a user's troubleshooting answers reachable. Those
 * answers live in a disposition ledger, and this class can see only raw disk state. Validating
 * here would re-derive a verdict the user has already overruled. So every shard-content problem is
 * reported from the apply phase instead, with those answers already applied. A stray shard, an
 * unparseable one, and a decision naming a file no montage showed all surface there.
 *
 * <p>{@code opts.allowPartial()} waives the missing-shard requirement outright, since that is the
 * only requirement this class has. {@code opts.timeout()} is ignored, since there is nothing to
 * wait on. The returned report counts a montage holding a shard as culled and a waived one as
 * skipped. It carries zero tokens: the judgement happened outside this app, so no model tokens
 * were spent here.
 */
@Component
class ExternalAgentCuller implements VisionCuller {

    /**
     * Returns this provider's identifier.
     *
     * @return {@link String} the manual-mode provider id
     */
    @Override
    public String id() {
        return VisionCuller.MANUAL_MODE_PROVIDER_ID;
    }

    /**
     * Checks the prep directory's shards with no progress reporting.
     *
     * @param prep {@link PrepDir} the prep directory to check
     * @param opts {@link CullOptions} cull options
     * @return {@link CullReport} the cull report
     * @throws CullException if any montage has no shard and allowPartial is off
     */
    @Override
    public CullReport cull(final PrepDir prep, final CullOptions opts) throws CullException {
        return this.cull(prep, opts, ProgressCallback.NO_OP);
    }

    /**
     * Verifies that every montage in the prep directory has a shard file, naming every montage
     * that doesn't in a single thrown exception.
     *
     * @param prep {@link PrepDir} the prep directory to check
     * @param opts {@link CullOptions} cull options
     * @param progress {@link ProgressCallback} progress callback ticked per montage
     * @return {@link CullReport} the cull report
     * @throws CullException if any montage has no shard and allowPartial is off
     */
    @Override
    public CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress) throws CullException {
        final var missing = new ArrayList<String>();
        final int total = prep.entries().size();
        int current = 0;
        for (final String montage : prep.entries()) {
            final String shardName = MontageNaming.shardFileFor(montage);
            if (!Files.exists(prep.prepDir().resolve(shardName))) {
                missing.add(montage + ": no shard " + shardName);
            }
            progress.tick(++current, total);
        }
        if (!opts.allowPartial() && !missing.isEmpty()) {
            throw new CullException("Cull for " + prep.scope() + " is incomplete ("
                    + missing.size() + " montage(s) still without a shard):\n - "
                    + String.join("\n - ", missing));
        }
        return new CullReport(total - missing.size(), missing.size(), 0, 0);
    }
}
