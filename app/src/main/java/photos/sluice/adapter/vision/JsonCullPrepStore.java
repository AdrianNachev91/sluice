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
// merged decisions.json this class writes is a distinct artifact from a per-montage shard. It gets
// its own small DTO here rather than reaching into ShardCodec's private encoding.
@Component
class JsonCullPrepStore implements CullPrepPort {

    private static final String NEAR_DUP_CHOSEN = "near-dup-chosen";
    private static final String NEAR_DUP_REJECT = "near-dup-reject";

    private final ShardCodec shardCodec;
    private final SidecarReader sidecarReader;
    private final JsonMapper mapper;

    @Autowired
    JsonCullPrepStore(ShardCodec shardCodec, SidecarReader sidecarReader) {
        this(shardCodec, sidecarReader, JsonMapper.builder().build());
    }

    // Package-private: lets a test inject a mock JsonMapper to exercise the JacksonException catch
    // branch, which a real write failure can't trigger deterministically.
    JsonCullPrepStore(ShardCodec shardCodec, SidecarReader sidecarReader, JsonMapper mapper) {
        this.shardCodec = shardCodec;
        this.sidecarReader = sidecarReader;
        this.mapper = mapper;
    }

    @Override
    public List<SidecarPhotoEntry> readSidecar(Path prepDir, String montage) {
        return sidecarReader.readEntries(prepDir.resolve(montage + ".json"));
    }

    @Override
    public boolean hasShard(Path prepDir, String montage) {
        return Files.exists(prepDir.resolve(MontageNaming.shardFileFor(montage)));
    }

    @Override
    public DecisionShard readShard(Path prepDir, String montage) {
        return shardCodec.read(prepDir.resolve(MontageNaming.shardFileFor(montage)));
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

    // Mirrors ShardCodec.toRaw's mapping shape, kept separate rather than shared: a per-montage
    // shard and the merged decisions.json are distinct artifacts with their own DTOs, free to diverge
    // later without coupling the two.
    @SuppressWarnings("DuplicatedCode")
    private static RawDecision toRaw(Decision decision) {
        return switch (decision) {
            case Classification c -> new RawDecision(c.file().toString(), c.category(), null, c.reason(), null);
            case NearDupChosen c -> new RawDecision(c.file().toString(), NEAR_DUP_CHOSEN, c.group(), null, c.chosenReason());
            case NearDupReject r -> new RawDecision(r.file().toString(), NEAR_DUP_REJECT, r.group(), r.reason(), null);
        };
    }
}
