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

// Routes a cull to the VisionCuller whose id matches the configured provider. Selection is the whole
// job here: the chosen culler owns how the shards are obtained. The registered cullers are indexed
// once by id at construction, where duplicate ids fail loud. The provider lookup runs on each cull()
// call, not in the constructor. That keeps construction total: it never fails just because no culler
// matches the configured provider yet. It also means the current provider value decides each call. An
// unknown provider fails loud.
@Component
public class CullDispatcher {

    private final Map<String, VisionCuller> byId;
    private final CullSettings settings;

    public CullDispatcher(List<VisionCuller> cullers, CullSettings settings) {
        this.byId = cullers.stream().collect(Collectors.toMap(
                VisionCuller::id, Function.identity(),
                (first, _) -> {
                    throw new IllegalStateException("Two vision cullers share id '" + first.id() + "'");
                }));
        this.settings = settings;
    }

    public CullReport cull(PrepDir prep, CullOptions options) throws CullException {
        return select().cull(prep, options);
    }

    public CullReport cull(PrepDir prep, CullOptions options, ProgressCallback progress) throws CullException {
        return select().cull(prep, options, progress);
    }

    public CullReport cull(PrepDir prep, CullOptions options, ProgressCallback progress, CancellationSignal cancellation)
            throws CullException {
        return select().cull(prep, options, progress, cancellation);
    }

    private VisionCuller select() {
        String provider = settings.provider();
        VisionCuller culler = byId.get(provider);
        if (culler == null) {
            throw new IllegalStateException("No vision culler registered for provider '" + provider
                    + "'. Registered: " + byId.keySet());
        }
        return culler;
    }
}
