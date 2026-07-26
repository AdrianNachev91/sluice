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

// Computes a WaitingCullJob's present/valid shard counts from its prep dir. Not a Spring bean -
// CullEngine owns the one instance it needs, built from the same CullPrepPort/CullSettings it
// already receives.
final class ShardTallyCalculator {

    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final ShardValidator shardValidator = new ShardValidator();

    ShardTallyCalculator(CullPrepPort cullPrepPort, CullSettings cullSettings) {
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
    }

    // present/valid computed per montage, one shard at a time, rather than through ApplyEngine's
    // own whole-batch validate(). A cross-shard problem (a near-dup group id reused across two
    // montages, a file claimed by two different shards) isn't caught here. That montage still
    // counts as valid.
    // That's an acceptable simplification for a progress-display number - the real gate stays
    // ApplyEngine.apply()'s full-batch validate(), unchanged by this tally.
    ShardTally tally(PrepDir prep) {
        List<Path> sidecarSrcs = prep.entries().stream()
                .flatMap(montage -> readSidecar(prep, montage).stream())
                .map(SidecarPhotoEntry::src)
                .toList();
        List<String> categories = cullSettings.categories().stream().map(CullCategory::name).toList();

        List<MontageShardStatus> statuses = prep.entries().stream()
                .map(montage -> montageShardStatus(prep, montage, sidecarSrcs, categories))
                .toList();
        int present = (int) statuses.stream().filter(MontageShardStatus::present).count();
        int valid = (int) statuses.stream().filter(MontageShardStatus::valid).count();
        return new ShardTally(present, valid, prep.entries().size());
    }

    // Cheap status check a CullWatcher polls repeatedly: does prepDir's tally already show every
    // montage present and valid? Deliberately not the heavier resume()/dispatchAndApply() path. A
    // transiently unreadable index (mid-write by a concurrent process) degrades to "not ready yet"
    // here rather than propagating - the same tolerance readWaitingJob() already gives this case.
    boolean isFullyValid(Path prepDir) {
        try {
            ShardTally shards = tally(cullPrepPort.readIndex(prepDir));
            return shards.valid() == shards.total();
        } catch (UncheckedIOException e) {
            return false;
        }
    }

    // A sidecar this app wrote itself during prep should always be readable. A transiently unreadable
    // one (mid-write by a concurrent cull job) degrades to "contributes no in-scope files" here,
    // rather than failing the whole tally. That's the same tolerance montageShardStatus() already
    // gives an unparseable shard below.
    private List<SidecarPhotoEntry> readSidecar(PrepDir prep, String montage) {
        try {
            return cullPrepPort.readSidecar(prep.prepDir(), montage);
        } catch (UncheckedIOException e) {
            return List.of();
        }
    }

    private MontageShardStatus montageShardStatus(PrepDir prep, String montage, List<Path> sidecarSrcs,
            List<String> categories) {
        if (!cullPrepPort.hasShard(prep.prepDir(), montage)) {
            return new MontageShardStatus(false, false);
        }
        try {
            var shardFile = new ShardFile(montage, cullPrepPort.readShard(prep.prepDir(), montage));
            var report = shardValidator.validate(List.of(shardFile), sidecarSrcs, categories, prep.unreviewable());
            return new MontageShardStatus(true, report.problems().isEmpty());
        } catch (UncheckedIOException e) {
            // Present but unparseable, so not valid.
            return new MontageShardStatus(true, false);
        }
    }

    private record MontageShardStatus(boolean present, boolean valid) {
    }
}
