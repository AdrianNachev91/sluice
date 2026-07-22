package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.PrepDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalAgentCullerTest {

    private final ShardCodec codec = new ShardCodec();
    private final ExternalAgentCuller culler = culler();

    @Test
    void idIsExternalAgent() {
        assertThat(culler.id()).isEqualTo("external-agent");
    }

    @Test
    void returnsWhenEveryMontageHasAValidShard(@TempDir Path dir) throws IOException {
        Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        Path keeper = dir.resolve("base").resolve("IMG_002.jpg");
        writeSidecar(dir, "montage-001", junk);
        writeSidecar(dir, "montage-002", keeper);
        codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a monitor"))));
        // An all-keeps montage still answers with a shard - an empty decisions list, not no file.
        codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThatCode(() -> culler.cull(prep(dir, "montage-001", "montage-002"), options()))
                .doesNotThrowAnyException();
    }

    @Test
    void throwsNamingEveryMontageMissingItsShard(@TempDir Path dir) throws IOException {
        writeSidecar(dir, "montage-001", dir.resolve("base").resolve("IMG_001.jpg"));
        writeSidecar(dir, "montage-002", dir.resolve("base").resolve("IMG_002.jpg"));

        assertThatThrownBy(() -> culler.cull(prep(dir, "montage-001", "montage-002"), options()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("montage-001: no shard decisions-001.json")
                .hasMessageContaining("montage-002: no shard decisions-002.json");
    }

    @Test
    void allowPartialWaivesMissingShards(@TempDir Path dir) throws IOException {
        Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        writeSidecar(dir, "montage-001", junk);
        writeSidecar(dir, "montage-002", dir.resolve("base").resolve("IMG_002.jpg"));
        codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "blurry document"))));

        assertThatCode(() -> culler.cull(prep(dir, "montage-001", "montage-002"), allowPartial()))
                .doesNotThrowAnyException();
    }

    @Test
    void allowPartialStillRejectsAnInvalidShard(@TempDir Path dir) throws IOException {
        Path photo = dir.resolve("base").resolve("IMG_001.jpg");
        writeSidecar(dir, "montage-001", photo);
        writeSidecar(dir, "montage-002", dir.resolve("base").resolve("IMG_002.jpg"));
        codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(photo, "blurry", "not a configured category"))));

        assertThatThrownBy(() -> culler.cull(prep(dir, "montage-001", "montage-002"), allowPartial()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("invalid action 'blurry'")
                .hasMessageNotContaining("no shard");
    }

    @Test
    void rejectsAShardWithNoMatchingMontage(@TempDir Path dir) throws IOException {
        Path junk = dir.resolve("base").resolve("IMG_001.jpg");
        writeSidecar(dir, "montage-001", junk);
        codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(junk, "junk", "photo of a receipt"))));
        codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThatThrownBy(() -> culler.cull(prep(dir, "montage-001"), options()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("decisions-002.json: no matching montage");
    }

    @Test
    void rejectsAStrayShardEvenWhenAllowPartialWaivesMissingOnes(@TempDir Path dir) throws IOException {
        writeSidecar(dir, "montage-001", dir.resolve("base").resolve("IMG_001.jpg"));
        codec.write(dir.resolve("decisions-002.json"), new DecisionShard("montage-002", List.of()));

        assertThatThrownBy(() -> culler.cull(prep(dir, "montage-001"), allowPartial()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("decisions-002.json: no matching montage")
                .hasMessageNotContaining("no shard decisions-001.json");
    }

    @Test
    void reportsAnUnparseableShardAmongTheRunsProblems(@TempDir Path dir) throws IOException {
        writeSidecar(dir, "montage-001", dir.resolve("base").resolve("IMG_001.jpg"));
        writeSidecar(dir, "montage-002", dir.resolve("base").resolve("IMG_002.jpg"));
        Files.writeString(dir.resolve("decisions-001.json"), "{ not json");

        assertThatThrownBy(() -> culler.cull(prep(dir, "montage-001", "montage-002"), options()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("decisions-001.json: ")
                .hasMessageContaining("montage-002: no shard decisions-002.json");
    }

    @Test
    void aggregatesValidatorProblemsIntoTheThrow(@TempDir Path dir) throws IOException {
        Path photo = dir.resolve("base").resolve("IMG_001.jpg");
        writeSidecar(dir, "montage-001", photo);
        codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(dir.resolve("elsewhere").resolve("OTHER.jpg"), "junk", "meme"),
                        new Classification(photo, "junk", ""))));

        assertThatThrownBy(() -> culler.cull(prep(dir, "montage-001"), options()))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("file out of scope")
                .hasMessageContaining("missing 'reason'");
    }

    @Test
    void healsADriftedPathThroughTheSidecarBasename(@TempDir Path dir) throws IOException {
        Path actual = dir.resolve("base").resolve("2019").resolve("06").resolve("IMG_001.jpg");
        writeSidecar(dir, "montage-001", actual);
        // The culler retyped the \YYYY\MM\ segment; the unique basename resolves it back into scope.
        Path drifted = dir.resolve("base").resolve("2019").resolve("07").resolve("IMG_001.jpg");
        codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(drifted, "junk", "photo of a screen"))));

        assertThatCode(() -> culler.cull(prep(dir, "montage-001"), options()))
                .doesNotThrowAnyException();
    }

    @Test
    void failsLoudWhenASidecarIsUnreadable(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("montage-001.json"), "{ not json");
        codec.write(dir.resolve("decisions-001.json"), new DecisionShard("montage-001", List.of()));

        assertThatThrownBy(() -> culler.cull(prep(dir, "montage-001"), options()))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("montage-001.json");
    }

    @Test
    void returnsCleanlyWhenThePrepDirHasNoMontages(@TempDir Path dir) {
        assertThatCode(() -> culler.cull(prep(dir), options()))
                .doesNotThrowAnyException();
    }

    private ExternalAgentCuller culler() {
        return new ExternalAgentCuller(new ShardCodec(), new SidecarReader(),
                new FixedSettings("external-agent", List.of(
                        new CullCategory("junk", "objectively worthless shots"),
                        new CullCategory("scenery", "unremarkable scenery"),
                        new CullCategory("food", "meal photos"),
                        new CullCategory("funny", "memes and funny screenshots"))));
    }

    private static CullOptions options() {
        return new CullOptions(false, null);
    }

    private static CullOptions allowPartial() {
        return new CullOptions(true, null);
    }

    private PrepDir prep(Path prepDir, String... entries) {
        return new PrepDir("2019-06", prepDir.resolve("base"), entries.length * 2, List.of(),
                entries.length, prepDir, List.of(entries));
    }

    // Writes a sidecar in the writer's on-disk shape, with the fields this adapter doesn't consume
    // present too, so reads are exercised against the real full document.
    private void writeSidecar(Path prepDir, String montage, Path... srcs) throws IOException {
        StringBuilder photos = new StringBuilder();
        for (Path src : srcs) {
            if (!photos.isEmpty()) {
                photos.append(", ");
            }
            photos.append("""
                    { "src": "%s", "name": "%s", "time": "2019-06-20T15:00:10Z", "received": false }"""
                    .formatted(jsonEscaped(src), src.getFileName()));
        }
        Files.writeString(prepDir.resolve(montage + ".json"), """
                {
                  "montage": "%s",
                  "photos": [ %s ]
                }
                """.formatted(jsonEscaped(prepDir.resolve(montage + ".jpg")), photos));
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private record FixedSettings(String provider, List<CullCategory> categories) implements CullSettings {
    }
}
