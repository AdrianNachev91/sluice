package photos.sluice.adapter.vision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and writes a {@code decisions-NNN.json} shard, the file an external vision agent drops
 * into the prep directory to record its non-keep decisions for one montage. The pure domain
 * {@link Decision} types carry no framework annotations. The whole JSON contract lives in the
 * private {@code RawDecision} DTO below. It maps the action string to a subtype: {@code
 * near-dup-chosen} and {@code near-dup-reject} give the two near-dup shapes. Any other action
 * value is a {@link Classification} whose category is that action string.
 *
 * <p>The read path splits two kinds of bad input. Anything that isn't a representable shard fails
 * loud: an unknown field, malformed JSON, a null document, or a null decision entry. None can be
 * turned into a {@link Decision}, and an unknown field can't even be seen once parsed. So the
 * codec is the only place to catch it. Everything representable-but-wrong is left for
 * {@link photos.sluice.domain.cull.ShardValidator}, the single source of truth for the shard
 * contract. An absent required field deserializes to null and becomes empty here. The validator
 * reports it ("missing reason", say), aggregated with the rest of the run's problems, not as a
 * first-error parse crash. Null DTO fields are omitted on write, so a classification shard
 * carries only file, action, and reason, and never emits an empty group key.
 */
@Component
class ShardCodec {

    private static final String NEAR_DUP_CHOSEN = "near-dup-chosen";
    private static final String NEAR_DUP_REJECT = "near-dup-reject";

    private final JsonMapper mapper;

    /**
     * Constructs the codec with the default JSON mapper.
     */
    ShardCodec() {
        this(JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());
    }

    /**
     * Package-private: lets a test inject a mock JsonMapper to exercise the JacksonException catch
     * branch, which a real read/write failure can't trigger deterministically.
     *
     * @param mapper {@link JsonMapper} the JSON mapper used for shard I/O
     */
    ShardCodec(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The JSON shape a single decision takes on disk. Every field is nullable so a malformed or
     * incomplete entry can still be parsed and reported, rather than crashing the whole read.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RawDecision(
            @Nullable String file,
            @Nullable String action,
            @Nullable String group,
            @Nullable String reason,
            @JsonProperty("chosen_reason") @Nullable String chosenReason) {
    }

    /**
     * The full JSON document a shard file holds: the montage name and its list of decisions.
     */
    private record RawShard(@Nullable String montage, @Nullable List<@Nullable RawDecision> decisions) {
    }

    /**
     * Writes a decision shard to disk.
     *
     * @param shardPath {@link Path} path of the shard file to write
     * @param shard {@link DecisionShard} the decision shard to write
     */
    public void write(final Path shardPath, final DecisionShard shard) {
        final var document = new RawShard(
                shard.montage(),
                shard.decisions().stream().map(ShardCodec::toRaw).toList());
        try (final var output = Files.newOutputStream(shardPath)) {
            this.mapper.writeValue(output, document);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write shard " + shardPath, e);
        } catch (final JacksonException e) {
            throw new UncheckedIOException("Failed to write shard " + shardPath, new IOException(e));
        }
    }

    /**
     * Reads a decision shard from disk.
     *
     * @param shardPath {@link Path} path of the shard file to read
     * @return {@link DecisionShard} the parsed decision shard
     */
    public DecisionShard read(final Path shardPath) {
        final RawShard raw;
        try (final var input = Files.newInputStream(shardPath)) {
            raw = this.mapper.readValue(input, RawShard.class);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read shard " + shardPath, e);
        } catch (final JacksonException e) {
            throw new UncheckedIOException("Failed to read shard " + shardPath, new IOException(e));
        }
        // A document that is not a JSON object (the literal null token) can't be represented as a
        // shard, so fail loud instead of coercing it into an empty one. The IDE binds the generic
        // result to the non-null RawShard type and can't see that null deserializes to null.
        //noinspection ConstantValue
        if (raw == null) {
            throw new UncheckedIOException("Shard " + shardPath + " is not a JSON object",
                    new IOException("null document"));
        }
        final List<@Nullable RawDecision> rawDecisions = raw.decisions() == null ? List.of() : raw.decisions();
        return new DecisionShard(
                orEmpty(raw.montage()),
                rawDecisions.stream().map(ShardCodec::toDomain).toList());
    }

    /**
     * Converts a domain decision to its raw DTO representation.
     *
     * @param decision {@link Decision} the domain decision to convert
     * @return {@link RawDecision} the raw DTO representation
     */
    private static RawDecision toRaw(final Decision decision) {
        // Structurally similar to toDomain()'s switch below, but it maps the opposite direction
        // over a different type. Collapsing the two into one generic mapper would cost clarity.
        //noinspection DuplicatedCode
        return switch (decision) {
            case final Classification c -> new RawDecision(c.file().toString(), c.category(), null, c.reason(), null);
            case final NearDupChosen c -> new RawDecision(c.file().toString(), NEAR_DUP_CHOSEN, c.group(), null, c.chosenReason());
            case final NearDupReject r -> new RawDecision(r.file().toString(), NEAR_DUP_REJECT, r.group(), r.reason(), null);
        };
    }

    /**
     * Converts a raw DTO to its domain decision representation.
     *
     * @param raw {@link RawDecision} the raw DTO to convert
     * @return {@link Decision} the domain decision
     */
    private static Decision toDomain(final @Nullable RawDecision raw) {
        if (raw == null) {
            throw new UncheckedIOException("Shard contains a null decision entry",
                    new IOException("null decision entry"));
        }
        final var file = Path.of(orEmpty(raw.file()));
        final String action = orEmpty(raw.action());
        return switch (action) {
            case NEAR_DUP_CHOSEN -> new NearDupChosen(file, orEmpty(raw.group()), orEmpty(raw.chosenReason()));
            case NEAR_DUP_REJECT -> new NearDupReject(file, orEmpty(raw.group()), orEmpty(raw.reason()));
            default -> new Classification(file, action, orEmpty(raw.reason()));
        };
    }

    /**
     * Returns the value, or an empty string if it's null.
     *
     * @param value {@link String} the value, possibly null
     * @return {@link String} the value, or empty string if null
     */
    private static String orEmpty(final @Nullable String value) {
        return value == null ? "" : value;
    }
}
