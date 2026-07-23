package photos.sluice.adapter.vision;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.cull.ValidationReport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

// The provider for a user whose vision judgement comes from an agent outside this app. That agent
// reads the montages and drops a decisions-NNN.json shard per montage into the prep directory on
// its own schedule. cull() is therefore a single-attempt completeness check, never a wait. It verifies that
// every montage has a present, contract-valid shard right now. Otherwise it throws CullException
// carrying every problem found: missing shards, unparseable shards, shards for montages that don't
// exist, and every contract violation ShardValidator reports. Whoever culls gets the whole to-fix
// list in one pass. The throw is the poke: fix the shards, run again.
//
// opts.allowPartial() waives only the missing-shard requirement. Whatever shards do exist must
// still be fully valid. opts.timeout() is ignored - there is nothing to wait on. The returned
// report counts waived montages as skipped and carries zero tokens: the judgement happened outside
// this app, so no model tokens were spent here.
//
// Two failure channels, split by who can fix them. Shard problems are the culling agent's to fix
// and go into the CullException report. The sidecars listing what each montage shows are this
// app's own output. One that can't be read means the prep dir is broken and the scope needs
// re-prepping, so that fails loud and unchecked instead.
@Component
class ExternalAgentCuller implements VisionCuller {

    private final ShardCodec shardCodec;
    private final SidecarReader sidecarReader;
    private final CullSettings settings;
    private final ShardValidator validator = new ShardValidator();

    ExternalAgentCuller(ShardCodec shardCodec, SidecarReader sidecarReader, CullSettings settings) {
        this.shardCodec = shardCodec;
        this.sidecarReader = sidecarReader;
        this.settings = settings;
    }

    @Override
    public String id() {
        return "external-agent";
    }

    @Override
    public CullReport cull(PrepDir prep, CullOptions opts) throws CullException {
        var problems = new ArrayList<String>();
        var shards = new ArrayList<ShardFile>();
        for (String montage : prep.entries()) {
            collectShard(prep.prepDir(), montage, opts.allowPartial(), shards, problems);
        }
        problems.addAll(strayShards(prep));

        List<Path> sidecarSrcs = prep.entries().stream()
                .flatMap(montage -> sidecarReader.readEntries(prep.prepDir().resolve(montage + ".json")).stream())
                .map(SidecarPhotoEntry::src)
                .toList();
        List<String> categoryNames = settings.categories().stream().map(CullCategory::name).toList();
        ValidationReport report = validator.validate(shards, sidecarSrcs, categoryNames);
        problems.addAll(report.problems());

        if (!problems.isEmpty()) {
            throw new CullException("Cull for " + prep.scope() + " is incomplete ("
                    + problems.size() + " problem(s)):\n - " + String.join("\n - ", problems));
        }
        // A problem-free run means every montage either yielded a valid shard or was waived by
        // allowPartial, so the waived count is what the shard list doesn't cover.
        return new CullReport(shards.size(), prep.entries().size() - shards.size(), 0, 0);
    }

    // Reads one montage's expected shard into the validation list, or records why it can't be. The
    // recordable reasons: a missing shard (waived by allowPartial), or one the codec can't
    // represent (malformed JSON, an unknown field, a null entry). A parse failure is reported like
    // any other contract violation rather than thrown, so it aggregates with the rest of the run's
    // problems.
    private void collectShard(Path prepDir, String montage, boolean allowPartial,
            List<ShardFile> shards, List<String> problems) {
        String shardName = MontageNaming.shardFileFor(montage);
        Path shardPath = prepDir.resolve(shardName);
        if (!Files.exists(shardPath)) {
            if (!allowPartial) {
                problems.add(montage + ": no shard " + shardName);
            }
            return;
        }
        try {
            shards.add(new ShardFile(montage, shardCodec.read(shardPath)));
        } catch (UncheckedIOException e) {
            problems.add(shardName + ": " + rootMessage(e));
        }
    }

    // A decisions file with no matching montage is a problem in its own right, even when
    // allowPartial waives missing ones. It usually means the culler numbered a shard wrong, and
    // its decisions would otherwise be silently ignored. Matched by prefix/suffix, not a strict
    // decisions-NNN pattern, so a mis-numbered name like decisions-01.json is caught too.
    private static List<String> strayShards(PrepDir prep) {
        List<String> expected = prep.entries().stream()
                .map(MontageNaming::shardFileFor)
                .toList();
        try (Stream<Path> files = Files.list(prep.prepDir())) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("decisions-") && name.endsWith(".json"))
                    .filter(name -> !expected.contains(name))
                    .sorted()
                    .map(name -> name + ": no matching montage")
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list prep dir " + prep.prepDir(), e);
        }
    }

    // The codec wraps its failures in one or two layers of carrier exceptions whose messages only
    // repeat the file name. The deepest cause holds the actually useful detail. A message-less
    // cause falls back to its toString, which at least names the exception type.
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return Objects.requireNonNullElse(root.getMessage(), root.toString());
    }
}
