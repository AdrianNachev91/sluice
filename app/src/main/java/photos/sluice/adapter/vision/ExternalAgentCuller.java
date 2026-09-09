package photos.sluice.adapter.vision;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.ProgressCallback;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Set;

/**
 * The {@link VisionCuller} provider for a user whose vision judgement comes from an agent outside
 * this app. That agent reads the montages and drops a {@code decisions-NNN.json} shard per montage
 * into the prep directory on its own schedule. Calling {@code cull()} is therefore a
 * single-attempt presence check, never a wait. It asks one question: which montages still have no
 * shard file at all. It throws {@link CullException} naming every montage that has none. The throw
 * is the poke - write the missing shards, run again.
 *
 * <p>Whether those shards are any good is deliberately not asked here. Apply's own gate is the
 * single validator of shard content, and one validator rather than two is what keeps a user's
 * troubleshooting answers reachable. Those answers live in a disposition ledger, and this class can
 * see only raw disk state. Validating here would re-derive a verdict the user has already
 * overruled.
 *
 * <p>{@code opts.allowPartial()} waives the missing-shard requirement outright, since that is the
 * only requirement this class has. {@code opts.timeout()} is ignored, since there is nothing to
 * wait on. The returned report counts a montage holding a shard as culled and a waived one as
 * skipped. It carries zero tokens: the judgement happened outside this app, so no model tokens
 * were spent here.
 */
@Component
class ExternalAgentCuller implements VisionCuller {

    static final String PROVIDER_ID = "external-agent";

    /**
     * {@inheritDoc}
     *
     * <p>The judging happens outside this app, so none of the model settings apply and there is
     * nothing to authenticate. It takes no settings of its own.
     *
     * <p>Named by what the agent has to be able to do rather than by where it runs. An agent hands
     * its answers back by writing them into a folder. One that can only reply in a chat window
     * cannot do this, wherever its model happens to live.
     */
    @Override
    public VisionProviderDescriptor describe() {
        return new VisionProviderDescriptor(PROVIDER_ID,
                "External agent (an agent on this computer that can write files, like Claude Cowork)",
                Set.of(), Set.of(), null, null, null, null);
    }

    /**
     * Says the judgements come from outside this app, so a caller reads a refusal as a pause.
     *
     * @return {@link ProviderType} always {@link ProviderType#MANUAL}
     */
    @Override
    public ProviderType type() {
        return ProviderType.MANUAL;
    }

    /**
     * Answers that there is nothing to check. The agent is a person's own tool, reached through a
     * folder, so this app holds no credential for it and could not test one.
     *
     * @return {@link ProviderCheck} always {@link ProviderCheck.NotApplicable}
     */
    @Override
    public ProviderCheck check() {
        return new ProviderCheck.NotApplicable();
    }

    /**
     * Answers that a run through this provider consumes nothing. The judging happens outside this
     * app, on whatever the user's own agent is billed for.
     *
     * @param prep {@link PrepDir} the prep directory a run would be made over
     * @return {@link SpendForecast} always {@link SpendForecast.NoSpend}
     */
    @Override
    public SpendForecast forecast(final PrepDir prep) {
        return new SpendForecast.NoSpend();
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
     * @param progress {@link ProgressCallback} progress callback ticked once for each montage that
     *        has a shard. One the agent never judged is not ticked, so a partial run's count rests
     *        below the montage total
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
            if (Files.exists(prep.prepDir().resolve(shardName))) {
                progress.tick(++current, total);
            } else {
                missing.add(montage + ": no shard " + shardName);
            }
        }
        if (!opts.allowPartial() && !missing.isEmpty()) {
            throw new CullException("The sifting for " + prep.scope() + " is incomplete ("
                    + missing.size() + " montage(s) still without a shard):\n - "
                    + String.join("\n - ", missing));
        }
        return new CullReport(total - missing.size(), missing.size(), 0, TokenSpend.none(PROVIDER_ID), false);
    }
}
