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

// Reads and writes a decisions-NNN.json shard - the file an external vision agent drops into the
// prep directory to record its non-keep decisions for one montage. The pure domain Decision types
// carry no framework annotations. The whole JSON contract lives in the private RawDecision DTO
// below. It maps the action string to a subtype: near-dup-chosen and near-dup-reject give the two
// near-dup shapes. Any other action value is a Classification whose category IS that action string.
//
// The read path splits two kinds of bad input. Anything that isn't a representable shard fails loud:
// an unknown field (FAIL_ON_UNKNOWN), malformed JSON, a null document, or a null decision entry.
// None can be turned into a Decision, and an unknown field can't even be seen once parsed, so the
// codec is the only place to catch it. Everything representable-but-wrong is left for ShardValidator,
// the single source of truth for the shard contract. An absent required field deserializes to null
// and becomes empty here, so the validator reports it ("missing reason", say) aggregated with the
// rest of the run's problems, not as a first-error parse crash. Null DTO fields are omitted on write,
// so a classification shard carries only file/action/reason and never emits an empty group key.
@Component
public class ShardCodec {

    private static final String NEAR_DUP_CHOSEN = "near-dup-chosen";
    private static final String NEAR_DUP_REJECT = "near-dup-reject";

    private final JsonMapper mapper;

    public ShardCodec() {
        this(JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());
    }

    // Package-private: lets a test inject a mock JsonMapper to exercise the JacksonException catch
    // branch, which a real read/write failure can't trigger deterministically.
    ShardCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RawDecision(
            @Nullable String file,
            @Nullable String action,
            @Nullable String group,
            @Nullable String reason,
            @JsonProperty("chosen_reason") @Nullable String chosenReason) {
    }

    private record RawShard(@Nullable String montage, @Nullable List<@Nullable RawDecision> decisions) {
    }

    public void write(Path shardPath, DecisionShard shard) {
        var document = new RawShard(
                shard.montage(),
                shard.decisions().stream().map(ShardCodec::toRaw).toList());
        try (var output = Files.newOutputStream(shardPath)) {
            mapper.writeValue(output, document);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write shard " + shardPath, e);
        } catch (JacksonException e) {
            throw new UncheckedIOException("Failed to write shard " + shardPath, new IOException(e));
        }
    }

    public DecisionShard read(Path shardPath) {
        final RawShard raw;
        try (var input = Files.newInputStream(shardPath)) {
            raw = mapper.readValue(input, RawShard.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read shard " + shardPath, e);
        } catch (JacksonException e) {
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
        List<@Nullable RawDecision> rawDecisions = raw.decisions() == null ? List.of() : raw.decisions();
        return new DecisionShard(
                orEmpty(raw.montage()),
                rawDecisions.stream().map(ShardCodec::toDomain).toList());
    }

    private static RawDecision toRaw(Decision decision) {
        return switch (decision) {
            case Classification c -> new RawDecision(c.file().toString(), c.category(), null, c.reason(), null);
            case NearDupChosen c -> new RawDecision(c.file().toString(), NEAR_DUP_CHOSEN, c.group(), null, c.chosenReason());
            case NearDupReject r -> new RawDecision(r.file().toString(), NEAR_DUP_REJECT, r.group(), r.reason(), null);
        };
    }

    private static Decision toDomain(@Nullable RawDecision raw) {
        if (raw == null) {
            throw new UncheckedIOException("Shard contains a null decision entry",
                    new IOException("null decision entry"));
        }
        var file = Path.of(orEmpty(raw.file()));
        String action = orEmpty(raw.action());
        return switch (action) {
            case NEAR_DUP_CHOSEN -> new NearDupChosen(file, orEmpty(raw.group()), orEmpty(raw.chosenReason()));
            case NEAR_DUP_REJECT -> new NearDupReject(file, orEmpty(raw.group()), orEmpty(raw.reason()));
            default -> new Classification(file, action, orEmpty(raw.reason()));
        };
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
