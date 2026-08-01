package photos.sluice.adapter.vision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.MalformedPrepJsonException;
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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The {@link CullPrepPort} implementation. It lives alongside {@link ShardCodec} and
 * {@link SidecarReader} in the same package. That lets it reuse their montage-sidecar and
 * per-montage-shard reading at package-private visibility. Neither class's access needs widening,
 * and no cross-adapter-subpackage dependency is added.
 *
 * <p>The merged {@code decisions.json} this class writes, and the {@code index.json} it reads
 * back, are each a distinct artifact from a per-montage shard. They get their own small DTOs here
 * rather than reaching into {@link ShardCodec}'s private encoding.
 *
 * <p>This class is public, unlike {@link ShardCodec} and {@link SidecarReader}: the apply-side
 * engines' own tests live outside this package and need a real {@link CullPrepPort}. Other engine
 * tests wire real adapters ({@code NioMediaStore}, {@code CsvLibraryHashIndex}) the same way,
 * instead of a fake.
 */
@Component
public class JsonCullPrepStore implements CullPrepPort {

    private static final String NEAR_DUP_CHOSEN = "near-dup-chosen";
    private static final String NEAR_DUP_REJECT = "near-dup-reject";

    private final ShardCodec shardCodec;
    private final SidecarReader sidecarReader;
    private final JsonMapper mapper;

    /**
     * Public and no-arg so a test in another package (ApplyPlannerTest) can build a real instance
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
    JsonCullPrepStore(final ShardCodec shardCodec, final SidecarReader sidecarReader) {
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
    JsonCullPrepStore(final ShardCodec shardCodec, final SidecarReader sidecarReader, final JsonMapper mapper) {
        this.shardCodec = shardCodec;
        this.sidecarReader = sidecarReader;
        this.mapper = mapper;
    }

    /**
     * The JSON shape {@link #readIndex} parses: one scope's prep directory index, as written by
     * this app's own prep step.
     */
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
    public PrepDir readIndex(final Path prepDir) {
        final Path path = prepDir.resolve("index.json");
        final RawIndex raw;
        try (final var input = Files.newInputStream(path)) {
            raw = this.mapper.readValue(input, RawIndex.class);
        } catch (final NoSuchFileException e) {
            // Absent entirely is not a transient read failure - it will never resolve on retry,
            // just like a genuinely malformed one. Diagnosed and remedied identically.
            throw new MalformedPrepJsonException("Prep index " + path + " does not exist", e);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read prep index " + path, e);
        } catch (final JacksonException e) {
            throw new MalformedPrepJsonException("Failed to parse prep index " + path, e);
        }
        // The IDE binds the generic result to the non-null RawIndex type and can't see that a
        // literal null document deserializes to null.
        //noinspection ConstantValue
        if (raw == null) {
            throw new MalformedPrepJsonException("Prep index " + path + " is not a JSON object",
                    new IOException("null document"));
        }
        final List<String> unreviewable = raw.unreviewable() == null ? List.of() : raw.unreviewable();
        final List<String> entries = raw.entries() == null ? List.of() : raw.entries();
        return new PrepDir(raw.scope(), requiredPath(raw.basePath(), "basePath", path), raw.photos(),
                unreviewable.stream().map(entry -> requiredPath(entry, "an unreviewable entry", path)).toList(),
                raw.montages(), requiredPath(raw.prepDir(), "prepDir", path), entries);
    }

    /**
     * Converts a required JSON string field to a {@link Path}, failing loud if it's null. A null
     * required field is malformed content, the same as unparseable JSON - never a
     * {@link NullPointerException} escaping from {@link Path#of}.
     *
     * @param value {@link String} the raw field value, possibly null
     * @param field {@link String} the field's name, used only for the error message
     * @param indexPath {@link Path} index.json's own path, used only for the error message
     * @return {@link Path} the field's value, converted
     */
    private static Path requiredPath(final @Nullable String value, final String field, final Path indexPath) {
        if (value == null) {
            throw new MalformedPrepJsonException("Prep index " + indexPath + " has a null " + field,
                    new IOException("null " + field));
        }
        return Path.of(value);
    }

    /**
     * The JSON shape {@link #writeIndex} serializes. Mirrors {@code PrepIndexWriter}'s own Index DTO
     * one field at a time, kept as a distinct type rather than shared. This reader/writer pair and
     * {@code CullMontageRenderer}'s own writer are separate call sites for the same JSON shape.
     * They're free to diverge later without coupling adapter subpackages - neither may depend on
     * the other's classes, per {@code ArchitectureTest.adaptersAreSiblings}.
     *
     * @param scope {@link String} the on-disk tag identifying this prep dir's scope
     * @param basePath {@link String} the base path reported for this scope
     * @param photos int count of candidates found
     * @param unreviewable a {@link List} of {@link String} candidates that couldn't render a judgeable tile
     * @param montages int count of montages generated
     * @param prepDir {@link String} the prep directory path
     * @param entries a {@link List} of {@link String} the montage entry filenames
     */
    private record RawIndexOut(String scope, String basePath, int photos, List<String> unreviewable,
                               int montages, String prepDir, List<String> entries) {
    }

    /**
     * Writes index.json wholesale - the recovery-time counterpart to {@code CullMontageRenderer}'s
     * own prep-time write, used only to persist an index {@link
     * photos.sluice.application.service.PrepDirRemedies#rebuildIndex} reconstructed from surviving
     * sidecars.
     *
     * @param prepDir {@link Path} the prep directory to write into
     * @param index {@link PrepDir} the index to persist
     */
    @Override
    @SuppressWarnings("DuplicatedCode")
    public void writeIndex(final Path prepDir, final PrepDir index) {
        final var document = new RawIndexOut(
                index.scope(),
                index.basePath().toString(),
                index.photos(),
                index.unreviewable().stream().map(Path::toString).toList(),
                index.montages(),
                index.prepDir().toString(),
                index.entries());
        final Path path = prepDir.resolve("index.json");
        try (final var output = Files.newOutputStream(path)) {
            this.mapper.writeValue(output, document);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write prep index " + path, e);
        } catch (final JacksonException e) {
            throw new UncheckedIOException("Failed to write prep index " + path, new IOException(e));
        }
    }

    /**
     * Reads one montage's sidecar photo entries.
     *
     * @param prepDir {@link Path} the prep directory
     * @param montage {@link String} the montage name
     * @return a {@link List} of {@link SidecarPhotoEntry}, the montage's sidecar photo entries
     */
    @Override
    public List<SidecarPhotoEntry> readSidecar(final Path prepDir, final String montage) {
        return this.sidecarReader.readEntries(prepDir.resolve(montage + ".json"));
    }

    /**
     * Checks whether a montage's shard file exists.
     *
     * @param prepDir {@link Path} the prep directory
     * @param montage {@link String} the montage name
     * @return boolean true if the montage's shard file exists
     */
    @Override
    public boolean hasShard(final Path prepDir, final String montage) {
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
    public DecisionShard readShard(final Path prepDir, final String montage) {
        return this.shardCodec.read(prepDir.resolve(MontageNaming.shardFileFor(montage)));
    }

    /**
     * Reads a shard file directly by its own path.
     *
     * @param shardFile {@link Path} the shard file's own path
     * @return {@link DecisionShard} the parsed decision shard
     */
    @Override
    public DecisionShard readShardFile(final Path shardFile) {
        return this.shardCodec.read(shardFile);
    }

    /**
     * The JSON shape one decision takes inside the merged {@code decisions.json}. Null-valued
     * fields (a classification's unused {@code group}, say) are omitted on write.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RawDecision(String file, String action, @Nullable String group, @Nullable String reason,
                               @JsonProperty("chosen_reason") @Nullable String chosenReason) {
    }

    /**
     * The run's tallies embedded alongside the decision list in {@code decisions.json}, mirroring
     * {@link ApplyReport}'s own fields.
     */
    private record Summary(int reviewed, Map<String, Integer> categories,
                           @JsonProperty("near_dup_groups") int nearDupGroups,
                           @JsonProperty("near_dup_rejects") int nearDupRejects, int unreviewable) {
    }

    /**
     * The full JSON document {@link #writeMergedDecisions} writes: the scope, every decision made
     * across the run, and the apply {@link Summary}.
     */
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
    public void writeMergedDecisions(final Path prepDir, final String scope, final List<Decision> decisions,
                                     final ApplyReport report) {
        final var document = new MergedDecisions(
                scope,
                decisions.stream().map(JsonCullPrepStore::toRaw).toList(),
                new Summary(report.reviewed(), report.byCategory(), report.nearDupGroups(),
                        report.nearDupRejects(), report.unreviewable()));
        final Path path = prepDir.resolve("decisions.json");
        try (final var output = Files.newOutputStream(path)) {
            this.mapper.writeValue(output, document);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write merged decisions " + path, e);
        } catch (final JacksonException e) {
            throw new UncheckedIOException("Failed to write merged decisions " + path, new IOException(e));
        }
    }

    /**
     * Mirrors ShardCodec.toRaw's mapping shape, kept separate rather than shared. A per-montage
     * shard and the merged decisions.json are distinct artifacts with their own DTOs, free to
     * diverge later without coupling the two.
     *
     * @param decision {@link Decision} the domain decision to convert
     * @return {@link RawDecision} the raw DTO representation
     */
    @SuppressWarnings("DuplicatedCode")
    private static RawDecision toRaw(final Decision decision) {
        return switch (decision) {
            case final Classification c -> new RawDecision(c.file().toString(), c.category(), null, c.reason(), null);
            case final NearDupChosen c ->
                    new RawDecision(c.file().toString(), NEAR_DUP_CHOSEN, c.group(), null, c.chosenReason());
            case final NearDupReject reject ->
                    new RawDecision(reject.file().toString(), NEAR_DUP_REJECT, reject.group(), reject.reason(), null);
        };
    }
}
