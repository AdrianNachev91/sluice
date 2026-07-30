package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class JsonCullPrepStoreTest {

    private final JsonCullPrepStore store = new JsonCullPrepStore(new ShardCodec(), new SidecarReader());

    @Test
    void readsBackASidecarWrittenBySidecarWriter(@TempDir final Path dir) {
        final var photo = new SidecarPhotoEntry(dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-06-15T10:30:00Z"), false);
        new SidecarWriter().write(dir.resolve("montage-001.json"), dir.resolve("montage-001.jpg"), List.of(photo));

        final List<SidecarPhotoEntry> entries = store.readSidecar(dir, "montage-001");

        assertThat(entries).containsExactly(photo);
    }

    @Test
    void readsBackAnIndexWrittenByPrepIndexWriter(@TempDir final Path dir) {
        final var prepDir = new PrepDir("2019-06", dir.resolve("base"), 3, List.of(dir.resolve("skip.jpg")), 1, dir,
                List.of("montage-001"));
        new PrepIndexWriter().write(dir.resolve("index.json"), prepDir);

        assertThat(store.readIndex(dir)).isEqualTo(prepDir);
    }

    @Test
    void readIndexTreatsAMissingUnreviewableOrEntriesArrayAsEmpty(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("index.json"), """
                { "scope": "2019-06", "basePath": "%s", "photos": 0, "montages": 0, "prepDir": "%s" }
                """.formatted(jsonEscaped(dir.resolve("base")), jsonEscaped(dir)));

        final PrepDir prepDir = store.readIndex(dir);

        assertThat(prepDir.unreviewable()).isEmpty();
        assertThat(prepDir.entries()).isEmpty();
    }

    @Test
    void readIndexOnANullDocumentThrowsUnchecked(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("index.json"), "null");

        assertThatThrownBy(() -> store.readIndex(dir))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void readsBackAShardWrittenByShardCodec(@TempDir final Path dir) {
        final var shard = new DecisionShard("montage-002", List.of(
                new Classification(dir.resolve("junk.jpg"), "junk", "phone photo of a monitor")));
        new ShardCodec().write(dir.resolve("decisions-002.json"), shard);

        assertThat(store.readShard(dir, "montage-002")).isEqualTo(shard);
    }

    @Test
    void readShardOnMalformedContentThrowsUnchecked(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("decisions-003.json"), "{ not valid json");

        assertThatThrownBy(() -> store.readShard(dir, "montage-003"))
                .isInstanceOf(UncheckedIOException.class);
    }

    // readShardFile() is readShard()'s sibling for a stray shard, whose own filename names no real
    // montage - so it reads by the file's own path directly, rather than a montage-derived name.
    @Test
    void readShardFileReadsBackAShardByItsOwnPath(@TempDir final Path dir) {
        final var shard = new DecisionShard("montage-002", List.of(
                new Classification(dir.resolve("junk.jpg"), "junk", "phone photo of a monitor")));
        new ShardCodec().write(dir.resolve("decisions-003.json"), shard); // stray: no montage-003 entry anywhere

        assertThat(store.readShardFile(dir.resolve("decisions-003.json"))).isEqualTo(shard);
    }

    @Test
    void writeIndexRoundTripsThroughReadIndex(@TempDir final Path dir) {
        final var prepDir = new PrepDir("2019-06", dir.resolve("base"), 3, List.of(dir.resolve("skip.jpg")), 1, dir,
                List.of("montage-001"));

        store.writeIndex(dir, prepDir);

        assertThat(store.readIndex(dir)).isEqualTo(prepDir);
    }

    @Test
    void writeIndexReplacesWhateverIndexJsonHeldBefore(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("index.json"), "not valid json");
        final var rebuilt = new PrepDir("2019-06", dir.resolve("base"), 1, List.of(), 1, dir, List.of("montage-001"));

        store.writeIndex(dir, rebuilt);

        assertThat(store.readIndex(dir)).isEqualTo(rebuilt);
    }

    @Test
    void wrapsAnIndexWriteFailureIntoUncheckedIOException(@TempDir final Path dir) {
        final Path missingParent = dir.resolve("missing-parent");
        final var prepDir = new PrepDir("2019-06", dir.resolve("base"), 0, List.of(), 0, dir, List.of());

        assertThatThrownBy(() -> store.writeIndex(missingParent, prepDir))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(missingParent.resolve("index.json").toString());
    }

    @Test
    void wrapsAJacksonExceptionDuringIndexWriteIntoUncheckedIOException(@TempDir final Path dir) {
        final var mapper = mock(JsonMapper.class);
        doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(OutputStream.class), any());
        final var storeWithFailingMapper = new JsonCullPrepStore(new ShardCodec(), new SidecarReader(), mapper);
        final var prepDir = new PrepDir("2019-06", dir.resolve("base"), 0, List.of(), 0, dir, List.of());

        assertThatThrownBy(() -> storeWithFailingMapper.writeIndex(dir, prepDir))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void hasShardReflectsWhetherTheDecisionsFileExists(@TempDir final Path dir) {
        final var shard = new DecisionShard("montage-004", List.of());
        new ShardCodec().write(dir.resolve("decisions-004.json"), shard);

        assertThat(store.hasShard(dir, "montage-004")).isTrue();
        assertThat(store.hasShard(dir, "montage-005")).isFalse();
    }

    @Test
    void writesTheMergedDecisionsShapeWithDynamicCategories(@TempDir final Path dir) throws IOException {
        final Path junk = dir.resolve("a.jpg");
        final Path chosen = dir.resolve("b.jpg");
        final Path reject = dir.resolve("c.jpg");
        final List<Decision> decisions = List.of(
                new Classification(junk, "junk", "phone photo of a monitor"),
                new NearDupChosen(chosen, "lake-jun20", "sharpest of the burst"),
                new NearDupReject(reject, "lake-jun20", "softer focus"));
        final var report = new ApplyReport(3, Map.of("junk", 1), 0, 1, 1, List.of());

        store.writeMergedDecisions(dir, "2019-06", decisions, report);

        final String json = Files.readString(dir.resolve("decisions.json"), StandardCharsets.UTF_8);
        assertThat(json).isEqualToIgnoringWhitespace("""
                {
                  "scope": "2019-06",
                  "decisions": [
                    { "file": "%s", "action": "junk", "reason": "phone photo of a monitor" },
                    { "file": "%s", "action": "near-dup-chosen", "group": "lake-jun20", "chosen_reason": "sharpest of the burst" },
                    { "file": "%s", "action": "near-dup-reject", "group": "lake-jun20", "reason": "softer focus" }
                  ],
                  "summary": {
                    "reviewed": 3,
                    "categories": { "junk": 1 },
                    "near_dup_groups": 1,
                    "near_dup_rejects": 1,
                    "unreviewable": 0
                  }
                }
                """.formatted(jsonEscaped(junk), jsonEscaped(chosen), jsonEscaped(reject)));
    }

    @Test
    void wrapsAMergedWriteFailureIntoUncheckedIOException(@TempDir final Path dir) {
        final Path missingParent = dir.resolve("missing-parent");
        final var report = new ApplyReport(0, Map.of(), 0, 0, 0, List.of());

        assertThatThrownBy(() -> store.writeMergedDecisions(missingParent, "2019-06", List.of(), report))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(missingParent.resolve("decisions.json").toString());
    }

    @Test
    void wrapsAJacksonExceptionDuringMergedWriteIntoUncheckedIOException(@TempDir final Path dir) {
        final var mapper = mock(JsonMapper.class);
        doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(OutputStream.class), any());
        final var storeWithFailingMapper = new JsonCullPrepStore(new ShardCodec(), new SidecarReader(), mapper);
        final var report = new ApplyReport(0, Map.of(), 0, 0, 0, List.of());

        assertThatThrownBy(() -> storeWithFailingMapper.writeMergedDecisions(dir, "2019-06", List.of(), report))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
