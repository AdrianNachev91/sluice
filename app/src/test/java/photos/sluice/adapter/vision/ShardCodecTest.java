package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
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

class ShardCodecTest {

    private final ShardCodec codec = new ShardCodec();

    @Test
    void roundTripsEveryDecisionSubtype(@TempDir Path dir) {
        var shard = new DecisionShard("montage-007", List.of(
                new Classification(dir.resolve("junk.jpg"), "junk", "phone photo of a monitor"),
                new Classification(dir.resolve("plate.jpg"), "food", "ordinary restaurant plate"),
                new NearDupChosen(dir.resolve("best.jpg"), "lake-jun20", "sharpest of the burst"),
                new NearDupReject(dir.resolve("soft.jpg"), "lake-jun20", "softer focus")));
        Path shardPath = dir.resolve("decisions-007.json");

        codec.write(shardPath, shard);

        assertThat(codec.read(shardPath)).isEqualTo(shard);
    }

    @Test
    void roundTripsAnAllKeepsMontageAsAnEmptyDecisionsShard(@TempDir Path dir) throws IOException {
        var shard = new DecisionShard("montage-008", List.of());
        Path shardPath = dir.resolve("decisions-008.json");

        codec.write(shardPath, shard);

        assertThat(Files.readString(shardPath, StandardCharsets.UTF_8)).isEqualToIgnoringWhitespace("""
                { "montage": "montage-008", "decisions": [] }""");
        assertThat(codec.read(shardPath)).isEqualTo(shard);
    }

    @Test
    void writesTheExactOnDiskShapeExternalAgentsExpect(@TempDir Path dir) throws IOException {
        Path junk = dir.resolve("IMG-20190612-WA0003.jpg");
        Path chosen = dir.resolve("IMG_20190620_150010.jpg");
        Path reject = dir.resolve("IMG_20190620_150012.jpg");
        var shard = new DecisionShard("montage-007", List.of(
                new Classification(junk, "junk", "phone photo of a monitor showing a webpage"),
                new NearDupChosen(chosen, "lake-jun20", "sharpest of the 3-shot burst"),
                new NearDupReject(reject, "lake-jun20", "softer focus; chosen is IMG_20190620_150010.jpg")));
        Path shardPath = dir.resolve("decisions-007.json");

        codec.write(shardPath, shard);

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
    void classificationOmitsGroupAndChosenReasonKeys(@TempDir Path dir) throws IOException {
        var shard = new DecisionShard("montage-001", List.of(
                new Classification(dir.resolve("a.jpg"), "scenery", "weak composition")));
        Path shardPath = dir.resolve("decisions-001.json");

        codec.write(shardPath, shard);

        String json = Files.readString(shardPath, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("group").doesNotContain("chosen_reason");
    }

    @Test
    void readsAClassificationWhoseActionIsAUserDefinedCategory(@TempDir Path dir) throws IOException {
        Path shardPath = dir.resolve("decisions-002.json");
        Files.writeString(shardPath, """
                { "montage": "montage-002",
                  "decisions": [ { "file": "a.jpg", "action": "pets", "reason": "cat" } ] }""");

        DecisionShard shard = codec.read(shardPath);

        assertThat(shard.decisions()).singleElement().isInstanceOfSatisfying(Classification.class, c -> {
            assertThat(c.category()).isEqualTo("pets");
            assertThat(c.reason()).isEqualTo("cat");
        });
    }

    @Test
    void rejectsUnknownFieldsWhenReading(@TempDir Path dir) throws IOException {
        Path shardPath = dir.resolve("decisions-003.json");
        Files.writeString(shardPath, """
                { "montage": "montage-003",
                  "decisions": [ { "file": "a.jpg", "action": "junk", "reason": "blurry", "confidence": 0.9 } ] }""");

        assertThatThrownBy(() -> codec.read(shardPath))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(shardPath.toString());
    }

    @Test
    void leavesAbsentRequiredFieldsEmptyForTheValidatorToReject(@TempDir Path dir) throws IOException {
        Path shardPath = dir.resolve("decisions-004.json");
        Files.writeString(shardPath, """
                { "montage": "montage-004",
                  "decisions": [ { "action": "junk" } ] }""");

        DecisionShard shard = codec.read(shardPath);

        assertThat(shard.decisions()).singleElement().isInstanceOfSatisfying(Classification.class, c -> {
            assertThat(c.file().toString()).isEmpty();
            assertThat(c.reason()).isEmpty();
        });
    }

    @Test
    void leavesAbsentNearDupFieldsEmptyForTheValidatorToReject(@TempDir Path dir) throws IOException {
        Path shardPath = dir.resolve("decisions-009.json");
        Files.writeString(shardPath, """
                { "montage": "montage-009",
                  "decisions": [ { "file": "a.jpg", "action": "near-dup-chosen" } ] }""");

        DecisionShard shard = codec.read(shardPath);

        assertThat(shard.decisions()).singleElement().isInstanceOfSatisfying(NearDupChosen.class, c -> {
            assertThat(c.group()).isEmpty();
            assertThat(c.chosenReason()).isEmpty();
        });
    }

    @Test
    void rejectsAnUnknownTopLevelField(@TempDir Path dir) throws IOException {
        Path shardPath = dir.resolve("decisions-010.json");
        Files.writeString(shardPath, """
                { "montage": "montage-010", "summary": "all good",
                  "decisions": [] }""");

        assertThatThrownBy(() -> codec.read(shardPath))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(shardPath.toString());
    }

    @Test
    void readsAMissingDecisionsArrayAsEmpty(@TempDir Path dir) throws IOException {
        Path shardPath = dir.resolve("decisions-005.json");
        Files.writeString(shardPath, """
                { "montage": "montage-005" }""");

        assertThat(codec.read(shardPath).decisions()).isEmpty();
    }

    @Test
    void rejectsANullDocument(@TempDir Path dir) throws IOException {
        Path shardPath = dir.resolve("decisions-006.json");
        Files.writeString(shardPath, "null");

        assertThatThrownBy(() -> codec.read(shardPath))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(shardPath.toString());
    }

    @Test
    void rejectsANullDecisionElement(@TempDir Path dir) throws IOException {
        Path shardPath = dir.resolve("decisions-007.json");
        Files.writeString(shardPath, """
                { "montage": "montage-007",
                  "decisions": [ null, { "file": "a.jpg", "action": "junk", "reason": "blurry" } ] }""");

        assertThatThrownBy(() -> codec.read(shardPath))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("null decision entry");
    }

    @Test
    void wrapsAWriteFailureIntoUncheckedIOException(@TempDir Path dir) {
        Path shardPath = dir.resolve("missing-parent").resolve("decisions-006.json");
        var shard = new DecisionShard("montage-006", List.of(
                new Classification(dir.resolve("a.jpg"), "junk", "blurry")));

        assertThatThrownBy(() -> codec.write(shardPath, shard))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(shardPath.toString());
    }

    @Test
    void wrapsAJacksonExceptionDuringWriteIntoUncheckedIOException(@TempDir Path dir) {
        var mapper = mock(JsonMapper.class);
        doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(OutputStream.class), any());
        var codecWithFailingMapper = new ShardCodec(mapper);
        Path shardPath = dir.resolve("decisions-007.json");
        var shard = new DecisionShard("montage-007", List.of());

        assertThatThrownBy(() -> codecWithFailingMapper.write(shardPath, shard))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(shardPath.toString())
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void wrapsAMalformedJsonReadIntoUncheckedIOException(@TempDir Path dir) throws IOException {
        Path shardPath = dir.resolve("decisions-008.json");
        Files.writeString(shardPath, "{ not valid json");

        assertThatThrownBy(() -> codec.read(shardPath))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(shardPath.toString())
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
