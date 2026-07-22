package photos.sluice.adapter.vision;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnthropicCullerTest {

    private static final List<CullCategory> CARDS = List.of(
            new CullCategory("junk", "Objectively worthless photos."),
            new CullCategory("scenery", "Unremarkable scenery."));
    private static final CullOptions OPTIONS = new CullOptions(false, null);

    @TempDir
    Path prepDir;

    private final AnthropicClient client = mock();
    private final MessageService messages = mock();

    @Test
    void reservesTheAnthropicProviderId() {
        assertThat(culler().id()).isEqualTo("anthropic");
    }

    @Test
    void writesAValidatedShardAndReportsTokenTotals() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg", "IMG_0004.jpg");
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" },
                    { "index": 2, "name": "IMG_0002.jpg", "action": "junk", "reason": "photo of a screen" },
                    { "index": 3, "name": "IMG_0003.jpg", "action": "near-dup-chosen", "group": "beach",
                      "chosen_reason": "sharpest of the burst" },
                    { "index": 4, "name": "IMG_0004.jpg", "action": "near-dup-reject", "group": "beach",
                      "reason": "blurrier than IMG_0003.jpg" }
                  ]
                }
                """, 1200, 340));

        CullReport report = culler().cull(prep, OPTIONS);

        DecisionShard shard = new ShardCodec().read(prepDir.resolve("decisions-001.json"));
        assertThat(shard.montage()).isEqualTo("montage-001");
        assertThat(shard.decisions()).containsExactly(
                new Classification(src("IMG_0002.jpg"), "junk", "photo of a screen"),
                new NearDupChosen(src("IMG_0003.jpg"), "beach", "sharpest of the burst"),
                new NearDupReject(src("IMG_0004.jpg"), "beach", "blurrier than IMG_0003.jpg"));
        assertThat(report).isEqualTo(new CullReport(1, 0, 1200, 340));
    }

    @Test
    void anAllKeepsResponseWritesAnEmptyShardMarkingTheMontageReviewed() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg", "IMG_0002.jpg");
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" },
                    { "index": 2, "name": "IMG_0002.jpg", "action": "keep" }
                  ]
                }
                """, 800, 90));

        culler().cull(prep, OPTIONS);

        DecisionShard shard = new ShardCodec().read(prepDir.resolve("decisions-001.json"));
        assertThat(shard.decisions()).isEmpty();
    }

    @Test
    void cullsEveryMontageSumsTokensAndClosesTheClient() throws Exception {
        writeMontage("montage-001", "IMG_0001.jpg");
        writeMontage("montage-002", "IMG_0002.jpg");
        respondWith(
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "screenshot" }
                          ]
                        }
                        """, 1000, 100),
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0002.jpg", "action": "keep" }
                          ]
                        }
                        """, 1100, 40));

        CullReport report = culler().cull(prep("montage-001", "montage-002"), OPTIONS);

        assertThat(prepDir.resolve("decisions-001.json")).exists();
        assertThat(prepDir.resolve("decisions-002.json")).exists();
        assertThat(report).isEqualTo(new CullReport(2, 0, 2100, 140));
        org.mockito.Mockito.verify(client).close();
    }

    // A stateless call cannot remember the slugs earlier montages picked, so the accumulated
    // cross-shard validation is what enforces group-id uniqueness across the run.
    @Test
    void failsLoudWhenTwoMontagesReuseANearDupGroupId() throws Exception {
        writeMontage("montage-001", "IMG_0001.jpg", "IMG_0002.jpg");
        writeMontage("montage-002", "IMG_0003.jpg", "IMG_0004.jpg");
        respondWith(
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0001.jpg", "action": "near-dup-chosen", "group": "beach",
                              "chosen_reason": "sharpest" },
                            { "index": 2, "name": "IMG_0002.jpg", "action": "near-dup-reject", "group": "beach",
                              "reason": "blurrier" }
                          ]
                        }
                        """, 1000, 100),
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0003.jpg", "action": "near-dup-chosen", "group": "beach",
                              "chosen_reason": "sharpest" },
                            { "index": 2, "name": "IMG_0004.jpg", "action": "near-dup-reject", "group": "beach",
                              "reason": "blurrier" }
                          ]
                        }
                        """, 1000, 100));

        assertThatThrownBy(() -> culler().cull(prep("montage-001", "montage-002"), OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("montage-002")
                .hasMessageContaining("near-dup group 'beach' spans 2 shards");
        assertThat(prepDir.resolve("decisions-001.json")).exists();
        assertThat(prepDir.resolve("decisions-002.json")).doesNotExist();
    }

    @Test
    void sendsSystemPromptMontageImageAndPhotoTable() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        culler().cull(prep, OPTIONS);

        var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        org.mockito.Mockito.verify(messages).create(captor.capture());
        MessageCreateParams request = captor.getValue();
        assertThat(request.model().asString()).isEqualTo("claude-sonnet-5");
        assertThat(request.system().orElseThrow().string().orElseThrow())
                .contains("### `junk`")
                .contains("When unsure, keep.");
        List<com.anthropic.models.messages.ContentBlockParam> blocks =
                request.messages().getFirst().content().blockParams().orElseThrow();
        String imageData = blocks.getFirst().image().orElseThrow()
                .source().base64().orElseThrow().data();
        assertThat(imageData).isEqualTo(
                Base64.getEncoder().encodeToString(Files.readAllBytes(prepDir.resolve("montage-001.jpg"))));
        assertThat(blocks.getLast().text().orElseThrow().text())
                .contains("Scope: 2019-06")
                .contains("1. IMG_0001.jpg");
        assertThat(request.outputConfig()).isPresent();
    }

    @Test
    void failsLoudWhenAVerdictNamesTheWrongPhoto() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "WRONG.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        assertThatThrownBy(() -> culler().cull(prep, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("verdict 1 names 'WRONG.jpg' but photo 1 is 'IMG_0001.jpg'");
        assertThat(prepDir.resolve("decisions-001.json")).doesNotExist();
    }

    @Test
    void failsLoudWhenAVerdictIsMissing() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg", "IMG_0002.jpg");
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        assertThatThrownBy(() -> culler().cull(prep, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("no verdict for photo 2 (IMG_0002.jpg)");
        assertThat(prepDir.resolve("decisions-001.json")).doesNotExist();
    }

    @Test
    void failsLoudWhenAnActionIsNotAConfiguredCategory() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "trash", "reason": "blurry" }
                  ]
                }
                """, 100, 10));

        assertThatThrownBy(() -> culler().cull(prep, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("invalid action 'trash'");
        assertThat(prepDir.resolve("decisions-001.json")).doesNotExist();
    }

    @Test
    void failsLoudWhenTheResponseIsNotTheVerdictJson() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        respondWith(response("not json at all", 100, 10));

        assertThatThrownBy(() -> culler().cull(prep, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("montage-001")
                .hasMessageContaining("not valid verdict JSON");
    }

    @Test
    void failsLoudWhenTheModelIsNotConfigured() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        var culler = new AnthropicCuller(cullerPrompt(settings(null)), new ShardCodec(),
                new SidecarReader(), settings(null),
                () -> { throw new AssertionError("client must not be built without a model"); });

        assertThatThrownBy(() -> culler.cull(prep, OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sluice.cull.provider-settings.model");
    }

    private AnthropicCuller culler() {
        CullSettings settings = settings("claude-sonnet-5");
        when(client.messages()).thenReturn(messages);
        return new AnthropicCuller(cullerPrompt(settings), new ShardCodec(), new SidecarReader(),
                settings, () -> client);
    }

    private void respondWith(Message first, Message... rest) {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(first, rest);
    }

    private static Message response(String json, long inputTokens, long outputTokens) {
        return Message.builder()
                .id("msg_test")
                .model("claude-sonnet-5")
                .stopReason(StopReason.END_TURN)
                .stopDetails(Optional.empty())
                .stopSequence(Optional.empty())
                .container(Optional.empty())
                .addContent(ContentBlock.ofText(TextBlock.builder()
                        .text(json)
                        .citations(List.of())
                        .build()))
                .usage(Usage.builder()
                        .inputTokens(inputTokens)
                        .outputTokens(outputTokens)
                        .cacheCreation(CacheCreation.builder()
                                .ephemeral1hInputTokens(0L)
                                .ephemeral5mInputTokens(0L)
                                .build())
                        .cacheCreationInputTokens(0L)
                        .cacheReadInputTokens(0L)
                        .inferenceGeo(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .outputTokensDetails(Optional.empty())
                        .build())
                .build();
    }

    private PrepDir prepWithOneMontage(String... names) throws IOException {
        writeMontage("montage-001", names);
        return prep("montage-001");
    }

    // One montage whose sidecar lists the given photos, plus its montage JPEG (any bytes do: the
    // culler only reads and encodes them).
    private void writeMontage(String montage, String... names) throws IOException {
        var photos = new StringBuilder();
        for (String name : names) {
            if (!photos.isEmpty()) {
                photos.append(",\n");
            }
            photos.append("""
                    { "src": "%s", "name": "%s", "time": "2019-06-20T15:00:10Z", "received": false }"""
                    .formatted(jsonEscaped(src(name)), name));
        }
        Files.writeString(prepDir.resolve(montage + ".json"), """
                {
                  "montage": "%s",
                  "photos": [ %s ]
                }
                """.formatted(montage, photos));
        Files.write(prepDir.resolve(montage + ".jpg"), new byte[] {1, 2, 3, 4});
    }

    private PrepDir prep(String... montages) {
        return new PrepDir("2019-06", prepDir.resolve("base"), 0, List.of(), montages.length,
                prepDir, List.of(montages));
    }

    private Path src(String name) {
        return prepDir.resolve("sorted").resolve(name);
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static CullerPrompt cullerPrompt(CullSettings settings) {
        return new CullerPrompt(settings, new MontageConfig(224, 5));
    }

    private static CullSettings settings(@Nullable String model) {
        return new FixedSettings("anthropic", CARDS, new CullProviderSettings(model, null));
    }

    private record FixedSettings(String provider, List<CullCategory> categories,
            CullProviderSettings providerSettings) implements CullSettings {
    }
}
