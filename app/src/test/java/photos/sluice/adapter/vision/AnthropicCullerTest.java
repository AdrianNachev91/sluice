package photos.sluice.adapter.vision;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        verify(client).close();
    }

    @Test
    void progressCallbackTicksOnceForEachMontageAgainstTheFinalMontageCount() throws Exception {
        writeMontage("montage-001", "IMG_0001.jpg");
        writeMontage("montage-002", "IMG_0002.jpg");
        respondWith(
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                          ]
                        }
                        """, 100, 10),
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0002.jpg", "action": "keep" }
                          ]
                        }
                        """, 100, 10));

        List<String> ticks = new ArrayList<>();
        culler().cull(prep("montage-001", "montage-002"), OPTIONS, (current, total) -> ticks.add(current + "/" + total));

        assertThat(ticks).containsExactly("1/2", "2/2");
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

    // index.json's unreviewable list has no shard of its own, but ApplyEngine moves it exactly like
    // a decision. So the same file appearing in both would double-move at apply time - the same
    // problem two shards reusing a group id would cause. Caught here, at cull time, not just later.
    @Test
    void failsLoudWhenAFileIsListedBothAsADecisionAndInTheUnreviewableList() throws Exception {
        writeMontage("montage-001", "IMG_0001.jpg");
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "screenshot" }
                  ]
                }
                """, 1000, 100));

        assertThatThrownBy(() -> culler().cull(prep(List.of(src("IMG_0001.jpg")), "montage-001"), OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("file listed 2 times across shards/unreviewable: " + src("IMG_0001.jpg"));
        assertThat(prepDir.resolve("decisions-001.json")).doesNotExist();
    }

    @Test
    void retriesOnceWithTheProblemListWhenTheFirstResponseFailsValidation() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        respondWith(
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "WRONG.jpg", "action": "keep" }
                          ]
                        }
                        """, 100, 10),
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "screenshot" }
                          ]
                        }
                        """, 120, 30));

        CullReport report = culler().cull(prep, OPTIONS);

        DecisionShard shard = new ShardCodec().read(prepDir.resolve("decisions-001.json"));
        assertThat(shard.decisions()).containsExactly(
                new Classification(src("IMG_0001.jpg"), "junk", "screenshot"));
        // Both attempts' tokens count: the failed first call cost real money too.
        assertThat(report).isEqualTo(new CullReport(1, 0, 220, 40));
        var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages, times(2)).create(captor.capture());
        MessageCreateParams retry = captor.getAllValues().getLast();
        assertThat(retry.messages()).hasSize(3);
        assertThat(retry.messages().get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(retry.messages().get(1).content().string().orElseThrow()).contains("WRONG.jpg");
        assertThat(retry.messages().get(2).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(retry.messages().get(2).content().string().orElseThrow())
                .contains("failed validation")
                .contains("verdict 1 names 'WRONG.jpg' but photo 1 is 'IMG_0001.jpg'")
                .contains("complete corrected verdict list");
    }

    // The retry budget is a hard cap of one corrective attempt: a model that fails the same
    // montage twice stops burning tokens right there.
    @Test
    void failsAfterOneRetryAggregatingBothAttemptsProblems() throws Exception {
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
                .hasMessageContaining("a corrective retry did not fix it")
                .hasMessageContaining("First attempt (1 problem(s))")
                .hasMessageContaining("Retry (1 problem(s))");
        verify(messages, times(2))
                .create(any(MessageCreateParams.class));
        assertThat(prepDir.resolve("decisions-001.json")).doesNotExist();
    }

    // A failed attempt must roll its tentative shard back out of the accepted set. Poisoned
    // leftovers would surface as phantom problems when a later montage is validated.
    @Test
    void aFailedFirstAttemptLeavesTheAcceptedSetCleanForLaterMontages() throws Exception {
        writeMontage("montage-001", "IMG_0001.jpg");
        writeMontage("montage-002", "IMG_0002.jpg");
        respondWith(
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0001.jpg", "action": "trash", "reason": "blurry" }
                          ]
                        }
                        """, 100, 10),
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "blurry" }
                          ]
                        }
                        """, 120, 30),
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0002.jpg", "action": "junk", "reason": "screenshot" }
                          ]
                        }
                        """, 200, 20));

        CullReport report = culler().cull(prep("montage-001", "montage-002"), OPTIONS);

        assertThat(new ShardCodec().read(prepDir.resolve("decisions-001.json")).decisions())
                .containsExactly(new Classification(src("IMG_0001.jpg"), "junk", "blurry"));
        assertThat(new ShardCodec().read(prepDir.resolve("decisions-002.json")).decisions())
                .containsExactly(new Classification(src("IMG_0002.jpg"), "junk", "screenshot"));
        assertThat(report).isEqualTo(new CullReport(2, 0, 420, 60));
    }

    // The API rejects empty text blocks, so a blank reply cannot be echoed verbatim on retry.
    @Test
    void aBlankResponseRetriesWithAPlaceholderEcho() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        respondWith(
                response("", 100, 10),
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                          ]
                        }
                        """, 120, 30));

        CullReport report = culler().cull(prep, OPTIONS);

        assertThat(new ShardCodec().read(prepDir.resolve("decisions-001.json")).decisions()).isEmpty();
        assertThat(report).isEqualTo(new CullReport(1, 0, 220, 40));
        var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages, times(2)).create(captor.capture());
        MessageCreateParams retry = captor.getAllValues().getLast();
        assertThat(retry.messages().get(1).content().string().orElseThrow()).isEqualTo("(empty response)");
        assertThat(retry.messages().get(2).content().string().orElseThrow())
                .contains("response carries no text content");
    }

    @Test
    void resumesAMontageWhoseValidShardAlreadyExists() throws Exception {
        writeMontage("montage-001", "IMG_0001.jpg");
        writeMontage("montage-002", "IMG_0002.jpg");
        new ShardCodec().write(prepDir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(src("IMG_0001.jpg"), "junk", "photo of a screen"))));
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0002.jpg", "action": "keep" }
                  ]
                }
                """, 500, 50));

        CullReport report = culler().cull(prep("montage-001", "montage-002"), OPTIONS);

        assertThat(report).isEqualTo(new CullReport(1, 1, 500, 50));
        var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages).create(captor.capture());
        // The one request that went out is montage-002's, still numbered 2 of 2: a skip does not
        // renumber the sheets that follow it.
        assertThat(captor.getValue().messages().getFirst().content().blockParams().orElseThrow()
                .getLast().text().orElseThrow().text()).contains("sheet 002 (2 of 2)");
    }

    @Test
    void reCullsAMontageWhoseExistingShardIsUnreadable() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        Files.writeString(prepDir.resolve("decisions-001.json"), "not a shard at all");
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "screenshot" }
                  ]
                }
                """, 100, 10));

        CullReport report = culler().cull(prep, OPTIONS);

        assertThat(report).isEqualTo(new CullReport(1, 0, 100, 10));
        DecisionShard shard = new ShardCodec().read(prepDir.resolve("decisions-001.json"));
        assertThat(shard.decisions()).containsExactly(
                new Classification(src("IMG_0001.jpg"), "junk", "screenshot"));
    }

    @Test
    void reCullsAMontageWhoseExistingShardBreaksTheContract() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        // Parseable, but a blank reason breaks the shard contract - resume must decline it.
        new ShardCodec().write(prepDir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(src("IMG_0001.jpg"), "junk", ""))));
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "screenshot" }
                  ]
                }
                """, 100, 10));

        CullReport report = culler().cull(prep, OPTIONS);

        assertThat(report).isEqualTo(new CullReport(1, 0, 100, 10));
        DecisionShard shard = new ShardCodec().read(prepDir.resolve("decisions-001.json"));
        assertThat(shard.decisions()).containsExactly(
                new Classification(src("IMG_0001.jpg"), "junk", "screenshot"));
    }

    // A resumed shard joins the accumulated set, so the cross-shard rules keep firing across the
    // resume boundary. A later montage cannot reuse a group id an earlier run's shard claimed.
    @Test
    void aResumedShardStillBlocksALaterGroupIdReuse() throws Exception {
        writeMontage("montage-001", "IMG_0001.jpg", "IMG_0002.jpg");
        writeMontage("montage-002", "IMG_0003.jpg", "IMG_0004.jpg");
        new ShardCodec().write(prepDir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new NearDupChosen(src("IMG_0001.jpg"), "beach", "sharpest"),
                        new NearDupReject(src("IMG_0002.jpg"), "beach", "blurrier"))));
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0003.jpg", "action": "near-dup-chosen", "group": "beach",
                      "chosen_reason": "sharpest" },
                    { "index": 2, "name": "IMG_0004.jpg", "action": "near-dup-reject", "group": "beach",
                      "reason": "blurrier" }
                  ]
                }
                """, 100, 10));

        assertThatThrownBy(() -> culler().cull(prep("montage-001", "montage-002"), OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("montage-002")
                .hasMessageContaining("near-dup group 'beach' spans 2 shards");
    }

    @Test
    void thinkingIsExplicitlyDisabledByDefault() throws Exception {
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
        verify(messages).create(captor.capture());
        assertThat(captor.getValue().thinking().orElseThrow().isDisabled()).isTrue();
        assertThat(captor.getValue().maxTokens()).isEqualTo(8192);
    }

    @Test
    void configuredThinkingSendsAdaptiveWithAHigherTokenCeiling() throws Exception {
        PrepDir prep = prepWithOneMontage("IMG_0001.jpg");
        respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        culler(settingsWithThinking()).cull(prep, OPTIONS);

        var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages).create(captor.capture());
        assertThat(captor.getValue().thinking().orElseThrow().isAdaptive()).isTrue();
        assertThat(captor.getValue().maxTokens()).isEqualTo(16384);
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
        verify(messages).create(captor.capture());
        MessageCreateParams request = captor.getValue();
        assertThat(request.model().asString()).isEqualTo("claude-sonnet-5");
        assertThat(request.system().orElseThrow().string().orElseThrow())
                .contains("### `junk`")
                .contains("When unsure, keep.");
        List<ContentBlockParam> blocks =
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
        return culler(settings("claude-sonnet-5"));
    }

    private AnthropicCuller culler(CullSettings settings) {
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
        return prep(List.of(), montages);
    }

    private PrepDir prep(List<Path> unreviewable, String... montages) {
        return new PrepDir("2019-06", prepDir.resolve("base"), 0, unreviewable, montages.length,
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
        return new FixedSettings("anthropic", CARDS,
                new CullProviderSettings(model, null, null, null));
    }

    private static CullSettings settingsWithThinking() {
        return new FixedSettings("anthropic", CARDS,
                new CullProviderSettings("claude-sonnet-5", null, true, null));
    }

    private record FixedSettings(String provider, List<CullCategory> categories,
            CullProviderSettings providerSettings) implements CullSettings {
    }
}
