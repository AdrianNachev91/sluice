package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShardCodecTest {

    private final ShardCodec codec = new ShardCodec();

    @Test
    void roundTripsEveryDecisionSubtype(@TempDir final Path dir) {
        final var shard = new DecisionShard("montage-007", List.of(
                new Classification(dir.resolve("junk.jpg"), "junk", "phone photo of a monitor"),
                new Classification(dir.resolve("plate.jpg"), "food", "ordinary restaurant plate"),
                new NearDupChosen(dir.resolve("best.jpg"), "lake-jun20", "sharpest of the burst"),
                new NearDupReject(dir.resolve("soft.jpg"), "lake-jun20", "softer focus")));
        final Path shardPath = dir.resolve("decisions-007.json");

        this.codec.write(shardPath, shard);

        assertThat(this.codec.read(shardPath)).isEqualTo(shard);
    }

    @Test
    void roundTripsAnAllKeepsMontageAsAnEmptyDecisionsShard(@TempDir final Path dir) throws IOException {
        final var shard = new DecisionShard("montage-008", List.of());
        final Path shardPath = dir.resolve("decisions-008.json");

        this.codec.write(shardPath, shard);

        assertThat(Files.readString(shardPath, StandardCharsets.UTF_8)).isEqualToIgnoringWhitespace("""
                { "montage": "montage-008", "decisions": [] }""");
        assertThat(this.codec.read(shardPath)).isEqualTo(shard);
    }

    @Test
    void writesTheExactOnDiskShapeExternalAgentsExpect(@TempDir final Path dir) throws IOException {
        final Path junk = dir.resolve("IMG-20190612-WA0003.jpg");
        final Path chosen = dir.resolve("IMG_20190620_150010.jpg");
        final Path reject = dir.resolve("IMG_20190620_150012.jpg");
        final var shard = new DecisionShard("montage-007", List.of(
                new Classification(junk, "junk", "phone photo of a monitor showing a webpage"),
                new NearDupChosen(chosen, "lake-jun20", "sharpest of the 3-shot burst"),
                new NearDupReject(reject, "lake-jun20", "softer focus; chosen is IMG_20190620_150010.jpg")));
        final Path shardPath = dir.resolve("decisions-007.json");

        this.codec.write(shardPath, shard);

        assertThat(Files.readString(shardPath, StandardCharsets.UTF_8)).isEqualToIgnoringWhitespace("""
                {
                  "montage": "montage-007",
                  "decisions": [
                    { "file": "%s", "action": "junk", "reason": "phone photo of a monitor showing a webpage" },
                    { "file": "%s", "action": "near-dup-chosen", "group": "lake-jun20", "chosen_reason": "sharpest of the 3-shot burst" },
                    { "file": "%s", "action": "near-dup-reject", "group": "lake-jun20", "reason": "softer focus; chosen is IMG_20190620_150010.jpg" }
                  ]
                }
                """.formatted(jsonEscaped(junk), jsonEscaped(chosen), jsonEscaped(reject)));
    }

    @Test
    void classificationOmitsGroupAndChosenReasonKeys(@TempDir final Path dir) throws IOException {
        final var shard = new DecisionShard("montage-001", List.of(
                new Classification(dir.resolve("a.jpg"), "scenery", "weak composition")));
        final Path shardPath = dir.resolve("decisions-001.json");

        this.codec.write(shardPath, shard);

        final String json = Files.readString(shardPath, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("group").doesNotContain("chosen_reason");
    }

    @Test
    void readsAClassificationWhoseActionIsAUserDefinedCategory(@TempDir final Path dir) throws IOException {
        final Path shardPath = dir.resolve("decisions-002.json");
        Files.writeString(shardPath, """
                { "montage": "montage-002",
                  "decisions": [ { "file": "a.jpg", "action": "pets", "reason": "cat" } ] }""");

        final DecisionShard shard = this.codec.read(shardPath);

        assertThat(shard.decisions()).singleElement().isInstanceOfSatisfying(Classification.class, c -> {
            assertThat(c.category()).isEqualTo("pets");
            assertThat(c.reason()).isEqualTo("cat");
        });
    }

    @Test
    void rejectsUnknownFieldsWhenReading(@TempDir final Path dir) throws IOException {
        final Path shardPath = dir.resolve("decisions-003.json");
        Files.writeString(shardPath, """
                { "montage": "montage-003",
                  "decisions": [ { "file": "a.jpg", "action": "junk", "reason": "blurry", "confidence": 0.9 } ] }""");

        assertThatThrownBy(() -> this.codec.read(shardPath))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining(shardPath.toString());
    }

    @Test
    void leavesAbsentRequiredFieldsEmptyForTheValidatorToReject(@TempDir final Path dir) throws IOException {
        final Path shardPath = dir.resolve("decisions-004.json");
        Files.writeString(shardPath, """
                { "montage": "montage-004",
                  "decisions": [ { "action": "junk" } ] }""");

        final DecisionShard shard = this.codec.read(shardPath);

        assertThat(shard.decisions()).singleElement().isInstanceOfSatisfying(Classification.class, c -> {
            assertThat(c.file().toString()).isEmpty();
            assertThat(c.reason()).isEmpty();
        });
    }

    @Test
    void leavesAbsentNearDupFieldsEmptyForTheValidatorToReject(@TempDir final Path dir) throws IOException {
        final Path shardPath = dir.resolve("decisions-009.json");
        Files.writeString(shardPath, """
                { "montage": "montage-009",
                  "decisions": [ { "file": "a.jpg", "action": "near-dup-chosen" } ] }""");

        final DecisionShard shard = this.codec.read(shardPath);

        assertThat(shard.decisions()).singleElement().isInstanceOfSatisfying(NearDupChosen.class, c -> {
            assertThat(c.group()).isEmpty();
            assertThat(c.chosenReason()).isEmpty();
        });
    }

    @Test
    void rejectsAnUnknownTopLevelField(@TempDir final Path dir) throws IOException {
        final Path shardPath = dir.resolve("decisions-010.json");
        Files.writeString(shardPath, """
                { "montage": "montage-010", "summary": "all good",
                  "decisions": [] }""");

        assertThatThrownBy(() -> this.codec.read(shardPath))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining(shardPath.toString());
    }

    @Test
    void readsAMissingDecisionsArrayAsEmpty(@TempDir final Path dir) throws IOException {
        final Path shardPath = dir.resolve("decisions-005.json");
        Files.writeString(shardPath, """
                { "montage": "montage-005" }""");

        assertThat(this.codec.read(shardPath).decisions()).isEmpty();
    }

    @Test
    void rejectsANullDocument(@TempDir final Path dir) throws IOException {
        final Path shardPath = dir.resolve("decisions-006.json");
        Files.writeString(shardPath, "null");

        assertThatThrownBy(() -> this.codec.read(shardPath))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining(shardPath.toString());
    }

    @Test
    void rejectsANullDecisionElement(@TempDir final Path dir) throws IOException {
        final Path shardPath = dir.resolve("decisions-007.json");
        Files.writeString(shardPath, """
                { "montage": "montage-007",
                  "decisions": [ null, { "file": "a.jpg", "action": "junk", "reason": "blurry" } ] }""");

        assertThatThrownBy(() -> this.codec.read(shardPath))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("null decision entry");
    }

    @Test
    void wrapsAWriteFailureIntoUncheckedIOException(@TempDir final Path dir) {
        final Path shardPath = dir.resolve("missing-parent").resolve("decisions-006.json");
        final var shard = new DecisionShard("montage-006", List.of(
                new Classification(dir.resolve("a.jpg"), "junk", "blurry")));

        assertThatThrownBy(() -> this.codec.write(shardPath, shard))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(shardPath.toString());
    }

    @Test
    void wrapsAJacksonExceptionDuringWriteIntoUncheckedIOException(@TempDir final Path dir) {
        final var mapper = mock(JsonMapper.class);
        doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(OutputStream.class), any());
        final var codecWithFailingMapper = new ShardCodec(mapper);
        final Path shardPath = dir.resolve("decisions-007.json");
        final var shard = new DecisionShard("montage-007", List.of());

        assertThatThrownBy(() -> codecWithFailingMapper.write(shardPath, shard))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(shardPath.toString())
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void wrapsAMalformedJsonReadIntoMalformedPrepJsonException(@TempDir final Path dir) throws IOException {
        final Path shardPath = dir.resolve("decisions-008.json");
        Files.writeString(shardPath, "{ not valid json");

        assertThatThrownBy(() -> this.codec.read(shardPath))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining(shardPath.toString())
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void readOnAMissingShardThrowsMalformedPrepJsonException(@TempDir final Path dir) {
        // Absent entirely is diagnosed the same as corrupt, never as a transient read failure - it
        // will never resolve on retry.
        assertThatThrownBy(() -> this.codec.read(dir.resolve("decisions-011.json")))
                .isInstanceOf(MalformedPrepJsonException.class);
    }

    @Test
    void readOnAReadFailureThrowsPlainUncheckedIOExceptionNotMalformed(@TempDir final Path dir) throws IOException {
        // A directory where the shard file belongs stands in for a read that fails while the
        // content itself is fine. A lock held by a backup process, a permission denial, and a cloud
        // placeholder that never hydrated all land the same way. That distinction is what keeps a
        // merely-unreadable shard from being reported as the culling agent's mistake.
        final Path shardPath = dir.resolve("decisions-012.json");
        Files.createDirectory(shardPath);

        assertThatThrownBy(() -> this.codec.read(shardPath))
                .isInstanceOf(UncheckedIOException.class)
                .isNotInstanceOf(MalformedPrepJsonException.class);
    }

    @Test
    // any(Class.class) is the only unambiguous matcher for the Class<T>-vs-TypeReference<T>
    // readValue overload. The raw type it forces is a Mockito-generics artifact, not a real cast risk.
    @SuppressWarnings("unchecked")
    void readOnAWrappedReadFailureThrowsPlainUncheckedIOExceptionNotMalformed(@TempDir final Path dir)
            throws IOException {
        // The directory seam above only ever exercises one platform's failure path. This proves the
        // classification directly. Whenever a stream opens fine and fails on a later read, Jackson
        // wraps the underlying IOException into a JacksonIOException rather than letting it propagate.
        final Path shardPath = dir.resolve("decisions-013.json");
        Files.writeString(shardPath, "{}");
        final var wrapped = new IOException("simulated mid-stream read failure");
        final var jacksonIoException = mock(JacksonIOException.class);
        when(jacksonIoException.getCause()).thenReturn(wrapped);
        final var mapper = mock(JsonMapper.class);
        doThrow(jacksonIoException).when(mapper).readValue(any(InputStream.class), any(Class.class));
        final var codecWithFailingMapper = new ShardCodec(mapper);

        assertThatThrownBy(() -> codecWithFailingMapper.read(shardPath))
                .isInstanceOf(UncheckedIOException.class)
                .isNotInstanceOf(MalformedPrepJsonException.class)
                .hasCause(wrapped);
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
