package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonCullPrepStoreTest {

    private static final CullCategory JUNK = CullCategory.of("junk", "objectively worthless");
    private static final CullCategory FOOD = CullCategory.of("food", "meals and menus");

    private final JsonCullPrepStore store = new JsonCullPrepStore(new ShardCodec(), new SidecarReader());

    @Test
    void readsBackASidecarWrittenBySidecarWriter(@TempDir final Path dir) {
        final var photo = new SidecarPhotoEntry(dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-06-15T10:30:00Z"),
                false);
        new SidecarWriter().write(dir.resolve("montage-001.json"), dir.resolve("montage-001.jpg"), List.of(photo));

        final List<SidecarPhotoEntry> entries = this.store.readSidecar(dir, "montage-001");

        assertThat(entries).containsExactly(photo);
    }

    @Test
    void hasShardReflectsWhetherTheDecisionsFileExists(@TempDir final Path dir) {
        final var shard = new DecisionShard("montage-004", List.of());
        new ShardCodec().write(dir.resolve("decisions-004.json"), shard);

        assertThat(this.store.hasShard(dir, "montage-004")).isTrue();
        assertThat(this.store.hasShard(dir, "montage-005")).isFalse();
    }

    @Nested
    class ReadingTheIndex {

        @Test
        void readsBackWhatPrepIndexWriterWrote(@TempDir final Path dir) {
            final var prepDir = new PrepDir("2019-06", List.of(JUNK, FOOD), dir.resolve("base"), 3,
                    List.of(dir.resolve("skip.jpg")), 1, dir, List.of("montage-001"));
            new PrepIndexWriter().write(dir.resolve("index.json"), prepDir);

            assertThat(JsonCullPrepStoreTest.this.store.readIndex(dir)).isEqualTo(prepDir);
        }

        @Test
        void dropsBlankAndNullExamplesRatherThanRefusingTheIndex(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless",
                          "examples": ["  pocket shots ", "   ", null, "lens caps"] }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThat(JsonCullPrepStoreTest.this.store.readIndex(dir).categories().getFirst().examples())
                    .containsExactly("pocket shots", "lens caps");
        }

        @Test
        void refusesADescriptionPastItsCeiling(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), oneCardIndex(dir,
                    "\"description\": \"" + "x".repeat(CullCategory.maxDescription() + 1) + "\""));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("description is longer than");
        }

        @Test
        void refusesAnExamplePastItsCeiling(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), oneCardIndex(dir,
                    "\"description\": \"worthless\", \"examples\": [\""
                            + "x".repeat(CullCategory.maxExample() + 1) + "\"]"));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("example longer than");
        }

        @Test
        void refusesMoreExamplesThanACardMayCarry(@TempDir final Path dir) throws IOException {
            final String tooMany = IntStream.rangeClosed(0, CullCategory.maxExamples())
                    .mapToObj(i -> "\"e" + i + "\"")
                    .collect(Collectors.joining(", "));
            Files.writeString(dir.resolve("index.json"), oneCardIndex(dir,
                    "\"description\": \"worthless\", \"examples\": [" + tooMany + "]"));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("offering more than");
        }

        @Test
        void treatsAMissingUnreviewableOrEntriesArrayAsEmpty(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            final PrepDir prepDir = JsonCullPrepStoreTest.this.store.readIndex(dir);

            assertThat(prepDir.unreviewable()).isEmpty();
            assertThat(prepDir.entries()).isEmpty();
        }

        @Test
        void refusesAnIndexWithNoCategoriesArray(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("no categories");
        }

        @Test
        void refusesANullElementAmongTheCategories(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" },
                        null
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("null entry in categories");
        }

        @Test
        void refusesANullElementAmongTheEntries(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": [
                        "montage-001",
                        null
                      ]
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("null entry in entries");
        }

        @Test
        void refusesACategoryThatCouldNotBecomeAFolder(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" },
                        { "name": "../Photos/2019/06", "description": "escapes the review root" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("../Photos/2019/06")
                    .hasMessageContaining("lower-case");
        }

        @Test
        void refusesABlankCategory(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "", "description": "objectively worthless" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("lower-case");
        }

        @Test
        void acceptsAnEmptyCategoriesArray(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThat(JsonCullPrepStoreTest.this.store.readIndex(dir).categories()).isEmpty();
        }

        @Test
        void refusesACategoryWithNoName(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "description": "objectively worthless" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("category with no name");
        }

        @Test
        void refusesACategoryWithABlankDescription(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "  " }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("category 'junk' with no description");
        }

        @Test
        void refusesABareCategoryName(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        "junk"
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class);
        }

        @Test
        void refusesARepeatedCategory(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" },
                        { "name": "food", "description": "meals and menus" },
                        { "name": "junk", "description": "a second card under one name" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "entries": []
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("repeats the category 'junk'");
        }

        @Test
        void refusesAnEntryThatIsNotAMontageId(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 1,
                      "entries": [
                        "../../../evil"
                      ]
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("is not a montage id");
        }

        @Test
        void refusesANullDocument(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), "null");

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class);
        }

        @Test
        void refusesMalformedJson(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), "{ not valid json");

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class);
        }

        @Test
        void refusesAMissingIndexFile(@TempDir final Path dir) {
            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class);
        }

        @Test
        void refusesANullBasePath(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" }
                      ],
                      "basePath": null,
                      "photos": 0,
                      "montages": 0
                    }
                    """);

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("basePath");
        }

        @Test
        void refusesANullScope(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": null,
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("scope");
        }

        @Test
        void answersTheDirectoryItWasReadFromRatherThanTheOneTheFileNames(
                @TempDir final Path dir, @TempDir final Path elsewhere) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "montages": 0,
                      "prepDir": "%s"
                    }
                    """.formatted(jsonEscaped(dir.resolve("base")), jsonEscaped(elsewhere)));

            assertThat(JsonCullPrepStoreTest.this.store.readIndex(dir).prepDir()).isEqualTo(dir);
        }

        @Test
        void refusesANullUnreviewableEntry(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" }
                      ],
                      "basePath": "%s",
                      "photos": 0,
                      "unreviewable": [
                        null
                      ],
                      "montages": 0
                    }
                    """.formatted(jsonEscaped(dir.resolve("base"))));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class);
        }

        // A NUL character is the one path character Path.of refuses on every platform, so the fixture
        // reaches InvalidPathException wherever the suite runs.
        @Test
        void refusesAPathComponentThisPlatformRejects(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), """
                    {
                      "scope": "2019-06",
                      "categories": [
                        { "name": "junk", "description": "objectively worthless" }
                      ],
                      "basePath": "bad\\u0000path",
                      "photos": 0,
                      "montages": 0
                    }
                    """);

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(MalformedPrepJsonException.class)
                    .hasMessageContaining("basePath");
        }

        @Test
        void raisesAnUncheckedIOExceptionForAFailedOpenRatherThanCallingTheIndexMalformed(@TempDir final Path dir)
                throws IOException {
            // A directory in place of index.json is what makes the open itself fail, rather than the
            // content parse.
            Files.createDirectory(dir.resolve("index.json"));

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readIndex(dir))
                    .isInstanceOf(UncheckedIOException.class)
                    .isNotInstanceOf(MalformedPrepJsonException.class);
        }

        @Test
        // any(Class.class) is the only unambiguous matcher for the Class<T>-vs-TypeReference<T>
        // readValue overload. The raw type it forces is a Mockito-generics artifact, not a real cast risk.
        @SuppressWarnings("unchecked")
        void raisesAnUncheckedIOExceptionForAFailureMidReadRatherThanCallingTheIndexMalformed(
                @TempDir final Path dir) throws IOException {
            // A stream that opens fine and then fails mid-read has no portable fixture, so the mapper
            // is stubbed to throw the JacksonIOException that shape produces.
            Files.writeString(dir.resolve("index.json"), "{}");
            final var wrapped = new IOException("simulated mid-stream read failure");
            final var jacksonIoException = mock(JacksonIOException.class);
            when(jacksonIoException.getCause()).thenReturn(wrapped);
            final var mapper = mock(JsonMapper.class);
            doThrow(jacksonIoException).when(mapper).readValue(any(InputStream.class), any(Class.class));
            final var storeWithFailingMapper = new JsonCullPrepStore(new ShardCodec(), new SidecarReader(), mapper);

            assertThatThrownBy(() -> storeWithFailingMapper.readIndex(dir))
                    .isInstanceOf(UncheckedIOException.class)
                    .isNotInstanceOf(MalformedPrepJsonException.class)
                    .hasCause(wrapped);
        }
    }

    @Nested
    class WritingTheIndex {

        @Test
        void writesAndReadsBackACardsExamplesInOrder(@TempDir final Path dir) {
            final var food = new CullCategory("food", "meals and menus",
                    List.of("restaurant plates", "home dinners"), Boolean.TRUE);
            final var prepDir = new PrepDir("2019-06", List.of(food), dir.resolve("base"), 3,
                    List.of(), 1, dir, List.of("montage-001"));

            JsonCullPrepStoreTest.this.store.writeIndex(dir, prepDir);

            assertThat(JsonCullPrepStoreTest.this.store.readIndex(dir).categories()).containsExactly(food);
        }

        @Test
        void aCardThatOffersNoExamplesWritesNoKeyAndReadsBackWithNone(@TempDir final Path dir) throws IOException {
            final var prepDir = new PrepDir("2019-06", List.of(JUNK), dir.resolve("base"), 3,
                    List.of(), 1, dir, List.of("montage-001"));

            JsonCullPrepStoreTest.this.store.writeIndex(dir, prepDir);

            assertThat(Files.readString(dir.resolve("index.json"), StandardCharsets.UTF_8))
                    .doesNotContain("examples");
            assertThat(JsonCullPrepStoreTest.this.store.readIndex(dir).categories().getFirst().examples()).isEmpty();
        }

        @Test
        void roundTripsThroughReadIndex(@TempDir final Path dir) {
            final var prepDir = new PrepDir("2019-06", List.of(JUNK, FOOD), dir.resolve("base"), 3,
                    List.of(dir.resolve("skip.jpg")), 1, dir, List.of("montage-001"));

            JsonCullPrepStoreTest.this.store.writeIndex(dir, prepDir);

            assertThat(JsonCullPrepStoreTest.this.store.readIndex(dir)).isEqualTo(prepDir);
        }

        @Test
        void replacesWhateverIndexJsonHeldBefore(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("index.json"), "not valid json");
            final var rebuilt = new PrepDir("2019-06", List.of(JUNK), dir.resolve("base"), 1, List.of(), 1, dir,
                    List.of("montage-001"));

            JsonCullPrepStoreTest.this.store.writeIndex(dir, rebuilt);

            assertThat(JsonCullPrepStoreTest.this.store.readIndex(dir)).isEqualTo(rebuilt);
        }

        @Test
        void wrapsAWriteFailureIntoUncheckedIOException(@TempDir final Path dir) {
            final Path missingParent = dir.resolve("missing-parent");
            final var prepDir = new PrepDir("2019-06", List.of(JUNK), dir.resolve("base"), 0, List.of(), 0, dir,
                    List.of());

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.writeIndex(missingParent, prepDir))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining(missingParent.resolve("index.json").toString());
        }

        @Test
        void wrapsAJacksonExceptionIntoUncheckedIOException(@TempDir final Path dir) {
            final var mapper = mock(JsonMapper.class);
            doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(OutputStream.class), any());
            final var storeWithFailingMapper = new JsonCullPrepStore(new ShardCodec(), new SidecarReader(), mapper);
            final var prepDir = new PrepDir("2019-06", List.of(JUNK), dir.resolve("base"), 0, List.of(), 0, dir,
                    List.of());

            assertThatThrownBy(() -> storeWithFailingMapper.writeIndex(dir, prepDir))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(IOException.class)
                    .cause().hasCauseInstanceOf(JacksonException.class);
        }

        @Test
        void aFailedWriteLeavesThePreviousIndexIntactAndNoTemporaryFileBehind(@TempDir final Path dir)
                throws IOException {
            JsonCullPrepStoreTest.this.store.writeIndex(dir, new PrepDir("2019-06", List.of(JUNK),
                    dir.resolve("base"), 3, List.of(), 0, dir, List.of()));
            final String before = Files.readString(dir.resolve("index.json"), StandardCharsets.UTF_8);
            final var mapper = mock(JsonMapper.class);
            doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(OutputStream.class), any());
            final var failing = new JsonCullPrepStore(new ShardCodec(), new SidecarReader(), mapper);

            assertThatThrownBy(() -> failing.writeIndex(dir, new PrepDir("2019-06", List.of(FOOD),
                    dir.resolve("base"), 9, List.of(), 0, dir, List.of())))
                    .isInstanceOf(UncheckedIOException.class);

            assertThat(Files.readString(dir.resolve("index.json"), StandardCharsets.UTF_8)).isEqualTo(before);
            try (final var entries = Files.list(dir)) {
                assertThat(entries).containsExactly(dir.resolve("index.json"));
            }
        }
    }

    @Nested
    class ReadingAShard {

        @Test
        void readsBackWhatShardCodecWrote(@TempDir final Path dir) {
            final var shard = new DecisionShard("montage-002", List.of(
                    new Classification(dir.resolve("junk.jpg"), "junk", "phone photo of a monitor")));
            new ShardCodec().write(dir.resolve("decisions-002.json"), shard);

            assertThat(JsonCullPrepStoreTest.this.store.readShard(dir, "montage-002")).isEqualTo(shard);
        }

        @Test
        void refusesMalformedContent(@TempDir final Path dir) throws IOException {
            Files.writeString(dir.resolve("decisions-003.json"), "{ not valid json");

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store.readShard(dir, "montage-003"))
                    .isInstanceOf(MalformedPrepJsonException.class);
        }

        @Test
        void readsOneBackByItsOwnPath(@TempDir final Path dir) {
            final var shard = new DecisionShard("montage-002", List.of(
                    new Classification(dir.resolve("junk.jpg"), "junk", "phone photo of a monitor")));
            new ShardCodec().write(dir.resolve("decisions-003.json"), shard); // stray: no montage-003 entry anywhere

            assertThat(JsonCullPrepStoreTest.this.store.readShardFile(dir.resolve("decisions-003.json")))
                    .isEqualTo(shard);
        }
    }

    @Nested
    class WritingTheMergedDecisions {

        @Test
        void writesTheShapeWithDynamicCategories(@TempDir final Path dir) throws IOException {
            final Path junk = dir.resolve("a.jpg");
            final Path chosen = dir.resolve("b.jpg");
            final Path reject = dir.resolve("c.jpg");
            final List<Decision> decisions = List.of(
                    new Classification(junk, "junk", "phone photo of a monitor"),
                    new NearDupChosen(chosen, "lake-jun20", "sharpest of the burst"),
                    new NearDupReject(reject, "lake-jun20", "softer focus"));
            final var report = new ApplyReport(3, Map.of("junk", 1), 0, 1, 1, List.of());

            JsonCullPrepStoreTest.this.store.writeMergedDecisions(dir, "2019-06", decisions, report);

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
        void aFailedWriteLeavesNoDecisionsFileBehindAtAll(@TempDir final Path dir) throws IOException {
            final var mapper = mock(JsonMapper.class);
            doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(OutputStream.class), any());
            final var failing = new JsonCullPrepStore(new ShardCodec(), new SidecarReader(), mapper);
            final var report = new ApplyReport(0, Map.of(), 0, 0, 0, List.of());

            assertThatThrownBy(() -> failing.writeMergedDecisions(dir, "2019-06", List.of(), report))
                    .isInstanceOf(UncheckedIOException.class);

            try (final var entries = Files.list(dir)) {
                assertThat(entries).isEmpty();
            }
        }

        @Test
        void wrapsAWriteFailureIntoUncheckedIOException(@TempDir final Path dir) {
            final Path missingParent = dir.resolve("missing-parent");
            final var report = new ApplyReport(0, Map.of(), 0, 0, 0, List.of());

            assertThatThrownBy(() -> JsonCullPrepStoreTest.this.store
                    .writeMergedDecisions(missingParent, "2019-06", List.of(), report))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining(missingParent.resolve("decisions.json").toString());
        }

        @Test
        void wrapsAJacksonExceptionIntoUncheckedIOException(@TempDir final Path dir) {
            final var mapper = mock(JsonMapper.class);
            doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(OutputStream.class), any());
            final var storeWithFailingMapper = new JsonCullPrepStore(new ShardCodec(), new SidecarReader(), mapper);
            final var report = new ApplyReport(0, Map.of(), 0, 0, 0, List.of());

            assertThatThrownBy(() -> storeWithFailingMapper.writeMergedDecisions(dir, "2019-06", List.of(), report))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(IOException.class)
                    .cause().hasCauseInstanceOf(JacksonException.class);
        }
    }

    private static String oneCardIndex(final Path dir, final String cardFieldsAfterTheName) {
        return """
                {
                  "scope": "2019-06",
                  "categories": [
                    { "name": "junk", %s }
                  ],
                  "basePath": "%s",
                  "photos": 0,
                  "montages": 0,
                  "entries": []
                }
                """.formatted(cardFieldsAfterTheName, jsonEscaped(dir.resolve("base")));
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
