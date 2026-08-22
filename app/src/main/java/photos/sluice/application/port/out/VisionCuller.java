package photos.sluice.application.port.out;

import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;

/**
 * The effect boundary application services use to turn a prepared montage directory into
 * per-montage decision shards. The judgement always comes from a model or agent the user supplies;
 * the app provides no vision of its own. The port abstracts only how those decisions arrive.
 *
 * <p>In one mode the user's agent reads the montages and writes the shards out of band. In another
 * the app calls the user's configured vision model and writes the shards from its response. Either
 * way the work stays inside {@link #cull}, so callers invoke it uniformly regardless of provider.
 * What a caller still has to tell apart is what a {@link CullException} out of {@link #cull} means,
 * and {@link #type} is what answers that.
 */
public interface VisionCuller {

    /**
     * How this provider presents itself to anything configuring it. Its name, which settings it
     * uses, and which of those it cannot run without. The credential it authenticates with, and
     * the models it offers.
     *
     * <p>Answered by the provider rather than assembled elsewhere, so a provider added later
     * arrives complete. Nothing outside it has to be edited for it to appear.
     *
     * <p>The identifier a dispatcher matches the configured provider against lives here too.
     *
     * @return {@link VisionProviderDescriptor} this provider's own description
     */
    VisionProviderDescriptor describe();

    /**
     * How this provider gets its judgements, which tells a caller what a {@link CullException} out
     * of {@link #cull} means.
     *
     * <p>No default. Guessing one would classify a provider that waits for a person as automated,
     * and its ordinary pause would then reach a user as a failed run.
     *
     * @return {@link ProviderType} this provider's own type
     */
    ProviderType type();

    /**
     * Asks whether the credential stored for this provider is accepted, and what that credential can
     * run.
     *
     * <p>Answers rather than throws. Every way this can fail is a state a person can act on. So each
     * one is a value to render, not an exception a caller has to classify.
     *
     * <p>It reads what is stored for this provider, not anything a screen is holding unsaved. So a
     * surface offering this alongside editable fields is reporting on the last save.
     *
     * <p>May be called often and at no notice, whenever a surface decides what it shows is out of
     * date. An implementation that can only answer by doing the provider's real work is the wrong
     * shape for it.
     *
     * <p>Settings tells the user this costs nothing, distinct from a cull. Verify that claim holds
     * for this provider's own account before shipping it. A provider with no free way to check a
     * credential breaks the claim. That is a decision to make out loud, amending both this and the
     * Settings copy stating it as free.
     *
     * <p>No default. A provider with nothing to authenticate answers
     * {@link ProviderCheck.NotApplicable} deliberately, rather than inheriting a claim it never
     * made.
     *
     * @return {@link ProviderCheck} what the provider said
     */
    ProviderCheck check();

    /**
     * Sibling of {@link #check()}, checked against the given settings rather than what is stored.
     * For a surface trying a connection setting before it is saved.
     *
     * <p>Defaulted to ignore the candidate and answer {@link #check()}. A provider with nothing a
     * screen could try out before saving is correct to do exactly that.
     *
     * @param candidate {@link CullProviderSettings} the connection settings to check
     * @return {@link ProviderCheck} what the provider said
     */
    default ProviderCheck check(final CullProviderSettings candidate) {
        return this.check();
    }

    /**
     * Counts what one call against prep would carry on the way in, without making it.
     *
     * <p>What the pre-run estimate is built from, and through it the spend ceiling. Callers ask it
     * before deciding whether to start a run, so it is asked on a path that has spent nothing yet.
     * {@link SpendForecast.Unknown} is the answer for a provider that cannot say without doing the
     * real work.
     *
     * <p>No default. A missing answer read as zero would leave the ceiling disarmed for exactly the
     * provider that needed it, and nothing would say so. A provider that consumes nothing says
     * {@link SpendForecast.NoSpend} deliberately.
     *
     * @param prep {@link PrepDir} the prep directory a run would be made over
     * @return {@link SpendForecast} what one call would carry, or why that is not known
     */
    SpendForecast forecast(PrepDir prep);

    /**
     * Obtains a decision shard for every montage in prep, by whatever means the implementation
     * gets its judgements. Returns a report of what the run did and spent, and throws
     * CullException when it cannot. The throw is the signal to whoever is culling to try again.
     * How far opts is honored varies by provider; each documents its own take.
     *
     * <p>An implementation does not vouch for the shards' content. That is the apply phase's to
     * judge, since it alone reads the user's own answers to earlier findings. So a culler checks
     * only what it is placed to check. The manual-mode provider checks that a shard is there at
     * all. A provider calling a model checks that model's response before writing it.
     *
     * @param prep {@link PrepDir} the prep directory holding montages to judge
     * @param opts {@link CullOptions} options controlling how the culler runs
     * @return {@link CullReport} a report of what the run did and spent
     */
    CullReport cull(PrepDir prep, CullOptions opts) throws CullException;

    /**
     * Progress-aware sibling of cull() above, ticked once per montage processed. Defaulted to
     * silently ignore progress so an implementation that doesn't override it still satisfies the
     * port. Each concrete culler overrides this one directly. Its plain cull() delegates to it
     * instead, so the real work lives in exactly one place.
     *
     * @param prep {@link PrepDir} the prep directory holding montages to judge
     * @param opts {@link CullOptions} options controlling how the culler runs
     * @param progress {@link ProgressCallback} callback ticked once per montage processed
     * @return {@link CullReport} a report of what the run did and spent
     */
    default CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress) throws CullException {
        return this.cull(prep, opts);
    }

    /**
     * Cancellation-aware sibling of the two above, checked between montages. Defaulted to ignore
     * cancellation so an implementation with nothing interruptible to check (the external-agent
     * provider's single presence check) still satisfies the port without overriding this one too.
     * An automated provider overrides it directly, the same way it overrides the progress-aware
     * cull() above.
     *
     * @param prep {@link PrepDir} the prep directory holding montages to judge
     * @param opts {@link CullOptions} options controlling how the culler runs
     * @param progress {@link ProgressCallback} callback ticked once per montage processed
     * @param cancellation {@link CancellationSignal} signal checked between montages
     * @return {@link CullReport} a report of what the run did and spent
     */
    default CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress,
                            final CancellationSignal cancellation)
            throws CullException {
        return this.cull(prep, opts, progress);
    }
}
