package photos.sluice.adapter.vision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CategoryName;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     *
     * <p>There is no {@code prepDir} field. {@link PrepDir} carries one, and its only trustworthy
     * source is the directory the index was read from. The file's own claim about where it lives is
     * not that. An index written by an older build still carries the key, and it is ignored.
     */
    private record RawIndex(@Nullable String scope, @Nullable List<@Nullable RawCategory> categories,
                            @Nullable String basePath, int photos,
                            @Nullable List<String> unreviewable, int montages,
                            @Nullable List<String> entries) {
    }

    /**
     * The JSON shape of one recorded category card. Both fields are nullable here and checked in
     * {@link #requiredCategories}. A card missing either one is then reported as a damaged index,
     * instead of crashing {@link CullCategory}'s own constructor.
     */
    private record RawCategory(@Nullable String name, @Nullable String description) {
    }

    /**
     * Reads a prep directory's index.json into a {@link PrepDir}.
     *
     * <p>The returned record's own {@code prepDir} is this call's argument, never a value off disk.
     * A culler is handed the record and nothing else, so that field is its only handle on the
     * directory it is working in. Taking it from the file would let a doctored index in one
     * directory send every downstream step to another.
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
        } catch (final JacksonIOException e) {
            // The stream opened fine and failed on a later read - a lock or a dropped network mount
            // arriving mid-read, not malformed content. Jackson wraps the underlying IOException
            // rather than letting it propagate, so it needs its own clause ahead of JacksonException.
            throw new UncheckedIOException("Failed to read prep index " + path, e.getCause());
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
        final List<String> entries = raw.entries() == null
                ? List.of()
                : montageIds(withoutNulls(raw.entries(), "entries", path), path);
        return new PrepDir(requiredText(raw.scope(), "scope", path), requiredCategories(raw.categories(), path),
                requiredPath(raw.basePath(), "basePath", path), raw.photos(),
                unreviewable.stream().map(entry -> requiredPath(entry, "an unreviewable entry", path)).toList(),
                raw.montages(), prepDir, entries);
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
                index.categories().stream().map(card -> new RawCategory(card.name(), card.description())).toList(),
                index.basePath().toString(),
                index.photos(),
                index.unreviewable().stream().map(Path::toString).toList(),
                index.montages(),
                index.entries());
        final Path path = prepDir.resolve("index.json");
        try {
            AtomicJsonWrite.write(path, this.mapper, document);
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
     * The JSON shape {@link #writeIndex} serializes. Mirrors {@code PrepIndexWriter}'s own Index DTO
     * one field at a time, kept as a distinct type rather than shared. This reader/writer pair and
     * {@code CullMontageRenderer}'s own writer are separate call sites for the same JSON shape.
     * They're free to diverge later without coupling adapter subpackages - neither may depend on
     * the other's classes, per {@code ArchitectureTest.adaptersAreSiblings}.
     *
     * @param scope {@link String} the on-disk tag identifying this prep dir's scope
     * @param categories a {@link List} of {@link RawCategory} the category cards this run was prepped under
     * @param basePath {@link String} the base path reported for this scope
     * @param photos int count of candidates found
     * @param unreviewable a {@link List} of {@link String} candidates that couldn't render a judgeable tile
     * @param montages int count of montages generated
     * @param entries a {@link List} of {@link String} the montage entry filenames
     */
    private record RawIndexOut(String scope, List<RawCategory> categories, String basePath, int photos,
                               List<String> unreviewable, int montages, List<String> entries) {
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
     * The category set the prep dir was culled under. Absent is malformed content, unlike an absent
     * {@code unreviewable} or {@code entries} just above. Those two have a true empty meaning: no
     * file was skipped, no montage was built. No run is ever prepped without a category set, and an
     * empty one serializes as {@code []} rather than vanishing. So a missing field can only mean
     * the index is damaged.
     *
     * <p>Reported as malformed rather than defaulted, because the default would have to be live
     * config. Silently judging a run against rules it was never culled under is exactly what
     * recording the field prevents. Malformed instead reaches {@link
     * photos.sluice.application.service.PrepDirRemedies#rebuildIndex}, which makes that same
     * substitution deliberately and says so.
     *
     * <p>Each name is checked against {@link CategoryName}, and the set against itself. A recorded
     * name goes on to become a folder that media is moved into, and {@code ShardValidator} judges a
     * shard's category against this very set. So a bad name recorded here would pass the one check
     * that stands between it and the move. {@code CullDestinations} refuses an escaping destination
     * as a second line, which is a guard on the resolved path rather than on the name. A repeat is
     * refused for the reason {@code Settings} refuses one on the config side, since two cards under
     * one name alias a single category.
     *
     * <p>A description is required and non-blank, the rule {@link CullCategory} enforces on the
     * config side. A blank one renders a hollow prompt section and quietly costs cull recall. Its
     * length is deliberately unbounded. It is prose a user writes, and config accepts it unbounded,
     * so a cap here would refuse an index the app itself could have produced.
     *
     * <p>Every field is checked before any card is built, so {@link CullCategory}'s own
     * {@link IllegalArgumentException} is unreachable from here. That exception would otherwise be
     * an unchecked escape route out of every caller's read-failure handling.
     *
     * @param values a {@link List} of {@link RawCategory} the raw field value, possibly null
     * @param indexPath {@link Path} index.json's own path, used only for the error message
     * @return a {@link List} of {@link CullCategory} the recorded category cards
     */
    private static List<CullCategory> requiredCategories(final @Nullable List<@Nullable RawCategory> values,
                                                         final Path indexPath) {
        if (values == null) {
            throw new MalformedPrepJsonException("Prep index " + indexPath + " has no categories",
                    new IOException("null categories"));
        }
        final var cards = new ArrayList<CullCategory>();
        final Set<String> seen = new HashSet<>();
        for (final RawCategory raw : values) {
            if (raw == null) {
                throw new MalformedPrepJsonException("Prep index " + indexPath + " has a null entry in categories",
                        new IOException("null categories entry"));
            }
            final String name = raw.name();
            if (name == null) {
                throw new MalformedPrepJsonException("Prep index " + indexPath + " has a category with no name",
                        new IOException("null category name"));
            }
            final String problem = CategoryName.problemWith(name);
            if (problem != null) {
                throw new MalformedPrepJsonException("Prep index " + indexPath + " has a category '" + name
                        + "' that " + problem, new IOException("unusable category name"));
            }
            if (!seen.add(name)) {
                throw new MalformedPrepJsonException("Prep index " + indexPath + " repeats the category '" + name
                        + "'", new IOException("duplicate category"));
            }
            final String description = raw.description();
            if (description == null || description.isBlank()) {
                throw new MalformedPrepJsonException("Prep index " + indexPath + " has a category '" + name
                        + "' with no description", new IOException("blank category description"));
            }
            cards.add(new CullCategory(name, description));
        }
        return cards;
    }

    /**
     * Holds every montage entry to the id shape this app's own prep step produces. An entry is not
     * only a label. {@link MontageNaming#shardFileFor} turns it into a filename resolved against
     * the prep dir, and {@code PrepDirRemedies} moves a stray shard to one of those names. An entry
     * off disk therefore chooses a move destination, which is what the id rule refuses it.
     *
     * @param values a {@link List} of {@link String} the parsed entries, non-null and null-free
     * @param indexPath {@link Path} index.json's own path, used only for the error message
     * @return a {@link List} of {@link String} the same values
     */
    private static List<String> montageIds(final List<String> values, final Path indexPath) {
        for (final String montage : values) {
            if (!MontageNaming.isMontageId(montage)) {
                throw new MalformedPrepJsonException("Prep index " + indexPath + " has an entry '" + montage
                        + "' that is not a montage id", new IOException("unusable montage entry"));
            }
        }
        return values;
    }

    /**
     * Rejects a null element inside a JSON string array. {@link PrepDir} copies its list components
     * defensively, and that copy throws on a null element. Left unchecked, the throw would be an
     * unchecked escape route out of every caller's read-failure handling.
     *
     * @param values a {@link List} of {@link String} the parsed array, non-null
     * @param field {@link String} the field's name, used only for the error message
     * @param indexPath {@link Path} index.json's own path, used only for the error message
     * @return a {@link List} of {@link String} the same values
     */
    private static List<String> withoutNulls(final List<String> values, final String field, final Path indexPath) {
        if (values.contains(null)) {
            throw new MalformedPrepJsonException("Prep index " + indexPath + " has a null entry in " + field,
                    new IOException("null " + field + " entry"));
        }
        return values;
    }

    /**
     * Requires a JSON string field to carry a value. Nothing downstream refuses a null one. It
     * reaches log lines, a refusal message and an automated provider's prompt as the word
     * {@code null}. The read is the only place left to call an absent value malformed.
     *
     * @param value {@link String} the raw field value, possibly null
     * @param field {@link String} the field's name, used only for the error message
     * @param indexPath {@link Path} index.json's own path, used only for the error message
     * @return {@link String} the field's value
     */
    private static String requiredText(final @Nullable String value, final String field, final Path indexPath) {
        if (value == null) {
            throw new MalformedPrepJsonException("Prep index " + indexPath + " has a null " + field,
                    new IOException("null " + field));
        }
        return value;
    }

    /**
     * Converts a required JSON string field to a {@link Path}. A value this platform cannot make a
     * path out of is malformed content, the same as unparseable JSON. Two shapes reach that: a null,
     * and a string carrying a character the filesystem forbids. Both would otherwise leave
     * {@link Path#of} as an unchecked escape route out of every caller's read-failure handling.
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
        try {
            return Path.of(value);
        } catch (final InvalidPathException e) {
            throw new MalformedPrepJsonException("Prep index " + indexPath + " has an unusable " + field
                    + ": " + value, new IOException(e));
        }
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
        // This file's mere existence is what marks a run COMPLETE. A torn one would leave the run
        // reading as finished while carrying a truncated record of what it did.
        try {
            AtomicJsonWrite.write(path, this.mapper, document);
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
