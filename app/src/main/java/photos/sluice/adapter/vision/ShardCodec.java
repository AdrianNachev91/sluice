package photos.sluice.adapter.vision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.Verdict;
import photos.sluice.domain.cull.Verdict.Keep;
import photos.sluice.domain.cull.VerdictAction;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and writes a {@code decisions-NNN.json} shard, the file an external vision agent drops
 * into the prep directory to record its verdict on every photo of one montage. The pure domain
 * {@link Verdict} types carry no framework annotations. The whole JSON contract lives in the
 * private {@code RawDecision} DTO below. It maps the action string to a subtype: {@code keep}
 * gives a {@link Keep}, and {@code near-dup-chosen} and {@code near-dup-reject} the two near-dup
 * shapes. Any other action value is a {@link Classification} whose category is that action string.
 *
 * <p>The read path splits two kinds of bad input. Anything that isn't a representable shard throws
 * {@link MalformedPrepJsonException}: an unknown field, malformed JSON, a null document, a null
 * decision entry, or a file name this platform cannot make a path out of. None can be turned into a
 * {@link Verdict}, and an unknown field can't even be seen once parsed. So the codec is the only
 * place to catch it. A read that merely failed, leaving
 * the content itself intact, throws a plain {@link UncheckedIOException} instead. That distinction
 * is what lets a caller report damaged content as a finding while letting a lock or a permission
 * denial propagate. Everything representable-but-wrong is left for
 * {@link photos.sluice.domain.cull.ShardValidator}, the single source of truth for the shard
 * contract. An absent required field deserializes to null and becomes empty here. The validator
 * reports it ("missing reason", say), aggregated with the rest of the run's problems, not as a
 * first-error parse crash. Null DTO fields are omitted on write, so a classification shard
 * carries only file, action, and reason, and never emits an empty group key.
 */
@Component
class ShardCodec {

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
                shard.verdicts().stream().map(ShardCodec::toRaw).toList());
        try {
            AtomicJsonWrite.write(shardPath, this.mapper, document);
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
        } catch (final NoSuchFileException e) {
            // Absent entirely is not a transient read failure - it will never resolve on retry,
            // just like a genuinely malformed one.
            throw new MalformedPrepJsonException("Shard " + shardPath + " does not exist", e);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read shard " + shardPath, e);
        } catch (final JacksonIOException e) {
            // The stream opened fine and failed on a later read - a lock or a dropped network mount
            // arriving mid-read, not malformed content. Jackson wraps the underlying IOException
            // rather than letting it propagate, so it needs its own clause ahead of JacksonException.
            throw new UncheckedIOException("Failed to read shard " + shardPath, e.getCause());
        } catch (final JacksonException e) {
            throw new MalformedPrepJsonException("Failed to parse shard " + shardPath, e);
        }
        // A document that is not a JSON object (the literal null token) can't be represented as a
        // shard. Fail loud instead of coercing it into an empty one. The IDE binds the generic
        // result to the non-null RawShard type and can't see that null deserializes to null.
        //noinspection ConstantValue
        if (raw == null) {
            throw new MalformedPrepJsonException("Shard " + shardPath + " is not a JSON object",
                    new IOException("null document"));
        }
        final List<@Nullable RawDecision> rawDecisions = raw.decisions() == null ? List.of() : raw.decisions();
        return new DecisionShard(
                orEmpty(raw.montage()),
                rawDecisions.stream().map(decision -> toDomain(decision, shardPath)).toList());
    }

    /**
     * Converts a domain verdict to its raw DTO representation.
     *
     * @param verdict {@link Verdict} the domain verdict to convert
     * @return {@link RawDecision} the raw DTO representation
     */
    private static RawDecision toRaw(final Verdict verdict) {
        // Structurally similar to toDomain()'s switch below, but it maps the opposite direction
        // over a different type. Collapsing the two into one generic mapper would cost clarity.
        //noinspection DuplicatedCode
        return switch (verdict) {
            case final Keep keep -> new RawDecision(keep.file().toString(), VerdictAction.KEEP, null, null, null);
            case final Classification c -> new RawDecision(c.file().toString(), c.category(), null, c.reason(), null);
            case final NearDupChosen c -> new RawDecision(c.file().toString(), VerdictAction.NEAR_DUP_CHOSEN,
                    c.group(), null, c.chosenReason());
            case final NearDupReject reject -> new RawDecision(reject.file().toString(),
                    VerdictAction.NEAR_DUP_REJECT, reject.group(), reject.reason(), null);
        };
    }

    /**
     * Converts a raw DTO to its domain verdict representation.
     *
     * @param raw {@link RawDecision} the raw DTO to convert
     * @param shardPath {@link Path} the shard's own path, used only for the error message
     * @return {@link Verdict} the domain verdict
     */
    private static Verdict toDomain(final @Nullable RawDecision raw, final Path shardPath) {
        if (raw == null) {
            throw new MalformedPrepJsonException("Shard " + shardPath + " has a null decision entry",
                    new IOException("null decision entry"));
        }
        final var file = decisionFile(raw.file(), shardPath);
        final String action = orEmpty(raw.action());
        return switch (action) {
            case VerdictAction.KEEP -> new Keep(file);
            case VerdictAction.NEAR_DUP_CHOSEN -> new NearDupChosen(file, orEmpty(raw.group()),
                    orEmpty(raw.chosenReason()));
            case VerdictAction.NEAR_DUP_REJECT -> new NearDupReject(file, orEmpty(raw.group()), orEmpty(raw.reason()));
            default -> new Classification(file, action, orEmpty(raw.reason()));
        };
    }

    /**
     * Converts a decision's raw file string to a {@link Path}. An absent one becomes the empty
     * path, which the validator then reports as a missing file alongside the run's other problems.
     * A string this platform cannot make a path out of at all is a different matter. It is content
     * only the agent that wrote it can fix, so it is malformed content rather than an
     * {@link InvalidPathException} escaping as a caller's unhandled crash.
     *
     * <p>A path naming a filesystem root and nothing else is refused the same way. It parses
     * cleanly and has no file name at all, so every later step that asks for one gets nothing back.
     * Refusing it here is what keeps that from surfacing as an unhandled crash further downstream.
     *
     * @param value {@link String} the decision's raw file string, possibly null
     * @param shardPath {@link Path} the shard's own path, used only for the error message
     * @return {@link Path} the decision's file path
     */
    private static Path decisionFile(final @Nullable String value, final Path shardPath) {
        final Path file;
        try {
            file = Path.of(orEmpty(value));
        } catch (final InvalidPathException e) {
            throw new MalformedPrepJsonException("Shard " + shardPath + " names an unusable file: " + value,
                    new IOException(e));
        }
        if (file.getFileName() == null) {
            throw new MalformedPrepJsonException(
                    "Shard " + shardPath + " names a whole filesystem root rather than a file: " + value,
                    new IOException("no file name"));
        }
        return file;
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
