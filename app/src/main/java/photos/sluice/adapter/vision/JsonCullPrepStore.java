package photos.sluice.adapter.vision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// The CullPrepPort implementation. Lives alongside ShardCodec and SidecarReader (same package). This
// lets it reuse their montage-sidecar and per-montage-shard reading at package-private visibility.
// Neither class's access needs widening, and no cross-adapter-subpackage dependency is added. The
// merged decisions.json this class writes, and the index.json it reads back, are each a distinct
// artifact from a per-montage shard. They get their own small DTOs here rather than reaching into
// ShardCodec's private encoding. Public (unlike ShardCodec/SidecarReader): ApplyEngine's own tests
// live outside this package and need a real CullPrepPort. Other engine tests wire real adapters
// (NioMediaStore, CsvLibraryHashIndex) the same way, instead of a fake.
@Component
public class JsonCullPrepStore implements CullPrepPort {

    private static final String NEAR_DUP_CHOSEN = "near-dup-chosen";
    private static final String NEAR_DUP_REJECT = "near-dup-reject";

    private final ShardCodec shardCodec;
    private final SidecarReader sidecarReader;
    private final JsonMapper mapper;

    /**
     * Public and no-arg so a test in another package (ApplyEngineTest) can build a real instance
     * without depending on the package-private ShardCodec/SidecarReader constructor parameters.
     * Unused by Spring, which resolves the @Autowired constructor below instead.
     */
    public JsonCullPrepStore() {
        this(new ShardCodec(), new SidecarReader());
    }

    /**
     * Constructs the store with the default JSON mapper.
     *
     * @param shardCodec {@link ShardCodec} reads and writes per-montage shards
     * @param sidecarReader {@link SidecarReader} reads per-montage sidecars
     */
    @Autowired
    JsonCullPrepStore(ShardCodec shardCodec, SidecarReader sidecarReader) {
        this(shardCodec, sidecarReader, JsonMapper.builder().build());
    }

    /**
     * Package-private: lets a test inject a mock JsonMapper to exercise the JacksonException catch
     * branch, which a real write failure can't trigger deterministically.
     *
     * @param shardCodec {@link ShardCodec} reads and writes per-montage shards
     * @param sidecarReader {@link SidecarReader} reads per-montage sidecars
     * @param mapper {@link JsonMapper} the JSON mapper used for index and merged-decisions I/O
     */
    JsonCullPrepStore(ShardCodec shardCodec, SidecarReader sidecarReader, JsonMapper mapper) {
        this.shardCodec = shardCodec;
        this.sidecarReader = sidecarReader;
        this.mapper = mapper;
    }

    private record RawIndex(String scope, String basePath, int photos, @Nullable List<String> unreviewable,
            int montages, String prepDir, @Nullable List<String> entries) {
    }

    /**
     * Reads a prep directory's index.json into a {@link PrepDir}.
     *
     * @param prepDir {@link Path} the prep directory to read
     * @return {@link PrepDir} the parsed prep directory index
     */
    @Override
    public PrepDir readIndex(Path prepDir) {
        Path path = prepDir.resolve("index.json");
        RawIndex raw;
        try (var input = Files.newInputStream(path)) {
            raw = mapper.readValue(input, RawIndex.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prep index " + path, e);
        } catch (JacksonException e) {
            throw new UncheckedIOException("Failed to read prep index " + path, new IOException(e));
        }
        // The IDE binds the generic result to the non-null RawIndex type and can't see that a
        // literal null document deserializes to null.
        //noinspection ConstantValue
        if (raw == null) {
            throw new UncheckedIOException("Prep index " + path + " is not a JSON object",
                    new IOException("null document"));
        }
        List<String> unreviewable = raw.unreviewable() == null ? List.of() : raw.unreviewable();
        List<String> entries = raw.entries() == null ? List.of() : raw.entries();
        return new PrepDir(raw.scope(), Path.of(raw.basePath()), raw.photos(),
                unreviewable.stream().map(Path::of).toList(), raw.montages(), Path.of(raw.prepDir()), entries);
    }

    /**
     * Reads one montage's sidecar photo entries.
     *
     * @param prepDir {@link Path} the prep directory
     * @param montage {@link String} the montage name
     * @return a {@link List} of {@link SidecarPhotoEntry}, the montage's sidecar photo entries
     */
    @Override
    public List<SidecarPhotoEntry> readSidecar(Path prepDir, String montage) {
        return sidecarReader.readEntries(prepDir.resolve(montage + ".json"));
    }

    /**
     * Checks whether a montage's shard file exists.
     *
     * @param prepDir {@link Path} the prep directory
     * @param montage {@link String} the montage name
     * @return boolean true if the montage's shard file exists
     */
    @Override
    public boolean hasShard(Path prepDir, String montage) {
        return Files.exists(prepDir.resolve(MontageNaming.shardFileFor(montage)));
    }

    /**
     * Reads one montage's decision shard.
     *
     * @param prepDir {@link Path} the prep directory
     * @param montage {@link String} the montage name
     * @return {@link DecisionShard} the montage's decision shard
     */
    @Override
    public DecisionShard readShard(Path prepDir, String montage) {
        return shardCodec.read(prepDir.resolve(MontageNaming.shardFileFor(montage)));
    }

    /**
     * Reads a shard file directly by its own path.
     *
     * @param shardFile {@link Path} the shard file's own path
     * @return {@link DecisionShard} the parsed decision shard
     */
    @Override
    public DecisionShard readShardFile(Path shardFile) {
        return shardCodec.read(shardFile);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RawDecision(String file, String action, @Nullable String group, @Nullable String reason,
            @JsonProperty("chosen_reason") @Nullable String chosenReason) {
    }

    private record Summary(int reviewed, Map<String, Integer> categories,
            @JsonProperty("near_dup_groups") int nearDupGroups,
            @JsonProperty("near_dup_rejects") int nearDupRejects, int unreviewable) {
    }

    private record MergedDecisions(String scope, List<RawDecision> decisions, Summary summary) {
    }

    /**
     * Writes the run's merged decisions.json, combining every decision with the apply summary.
     *
     * @param prepDir {@link Path} the prep directory to write into
     * @param scope {@link String} the cull scope
     * @param decisions a {@link List} of {@link Decision}, every decision made across the run
     * @param report {@link ApplyReport} the apply summary to embed
     */
    @Override
    public void writeMergedDecisions(Path prepDir, String scope, List<Decision> decisions, ApplyReport report) {
        var document = new MergedDecisions(
                scope,
                decisions.stream().map(JsonCullPrepStore::toRaw).toList(),
                new Summary(report.reviewed(), report.byCategory(), report.nearDupGroups(),
                        report.nearDupRejects(), report.unreviewable()));
        Path path = prepDir.resolve("decisions.json");
        try (var output = Files.newOutputStream(path)) {
            mapper.writeValue(output, document);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write merged decisions " + path, e);
        } catch (JacksonException e) {
            throw new UncheckedIOException("Failed to write merged decisions " + path, new IOException(e));
        }
    }

    /**
     * Mirrors ShardCodec.toRaw's mapping shape, kept separate rather than shared: a per-montage
     * shard and the merged decisions.json are distinct artifacts with their own DTOs, free to diverge
     * later without coupling the two.
     *
     * @param decision {@link Decision} the domain decision to convert
     * @return {@link RawDecision} the raw DTO representation
     */
    @SuppressWarnings("DuplicatedCode")
    private static RawDecision toRaw(Decision decision) {
        return switch (decision) {
            case Classification c -> new RawDecision(c.file().toString(), c.category(), null, c.reason(), null);
            case NearDupChosen c -> new RawDecision(c.file().toString(), NEAR_DUP_CHOSEN, c.group(), null, c.chosenReason());
            case NearDupReject r -> new RawDecision(r.file().toString(), NEAR_DUP_REJECT, r.group(), r.reason(), null);
        };
    }
}
