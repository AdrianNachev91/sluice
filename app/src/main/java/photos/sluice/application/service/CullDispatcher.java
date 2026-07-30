package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes a cull to the {@link VisionCuller} whose id matches the configured provider. Selection
 * is the whole job here: the chosen culler owns how the shards are obtained.
 *
 * <p>The registered cullers are indexed once by id at construction, where a duplicate id fails
 * loud. The provider lookup itself runs on every {@code cull} call rather than in the
 * constructor. Construction never fails just because no culler matches the configured provider
 * yet, and each call is decided by whichever provider value is current at that moment. An
 * unknown provider fails loud.
 */
@Component
public class CullDispatcher {

    private final Map<String, VisionCuller> byId;
    private final CullSettings settings;

    /**
     * Indexes the given cullers by id, failing loud on duplicate ids.
     *
     * @param cullers a {@link List} of {@link VisionCuller} the vision cullers to register
     * @param settings {@link CullSettings} cull provider configuration
     */
    public CullDispatcher(final List<VisionCuller> cullers, final CullSettings settings) {
        this.byId = cullers.stream().collect(Collectors.toMap(
                VisionCuller::id, Function.identity(),
                (first, _) -> {
                    throw new IllegalStateException("Two vision cullers share id '" + first.id() + "'");
                }));
        this.settings = settings;
    }

    /**
     * Culls using the configured provider.
     *
     * @param prep {@link PrepDir} the prep directory to cull
     * @param options {@link CullOptions} cull behavior options
     * @return {@link CullReport} the cull report
     */
    public CullReport cull(final PrepDir prep, final CullOptions options) throws CullException {
        return this.select().cull(prep, options);
    }

    /**
     * Culls using the configured provider, reporting progress.
     *
     * @param prep {@link PrepDir} the prep directory to cull
     * @param options {@link CullOptions} cull behavior options
     * @param progress {@link ProgressCallback} progress callback
     * @return {@link CullReport} the cull report
     */
    public CullReport cull(final PrepDir prep, final CullOptions options, final ProgressCallback progress) throws CullException {
        return this.select().cull(prep, options, progress);
    }

    /**
     * Culls using the configured provider, reporting progress and honoring cancellation.
     *
     * @param prep {@link PrepDir} the prep directory to cull
     * @param options {@link CullOptions} cull behavior options
     * @param progress {@link ProgressCallback} progress callback
     * @param cancellation {@link CancellationSignal} cancellation signal
     * @return {@link CullReport} the cull report
     */
    public CullReport cull(final PrepDir prep, final CullOptions options, final ProgressCallback progress, final CancellationSignal cancellation)
            throws CullException {
        return this.select().cull(prep, options, progress, cancellation);
    }

    /**
     * Looks up the vision culler registered for the configured provider.
     *
     * @return {@link VisionCuller} the selected vision culler
     */
    private VisionCuller select() {
        final String provider = this.settings.provider();
        final VisionCuller culler = this.byId.get(provider);
        if (culler == null) {
            throw new IllegalStateException("No vision culler registered for provider '" + provider
                    + "'. Registered: " + this.byId.keySet());
        }
        return culler;
    }
}
