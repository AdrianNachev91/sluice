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
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.job.WatchMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        assertThat(this.culler().id()).isEqualTo("anthropic");
    }

    @Test
    void writesAValidatedShardAndReportsTokenTotals() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg", "IMG_0004.jpg");
        this.respondWith(response("""
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

        final CullReport report = this.culler().cull(prep, OPTIONS);

        final DecisionShard shard = new ShardCodec().read(this.prepDir.resolve("decisions-001.json"));
        assertThat(shard.montage()).isEqualTo("montage-001");
        assertThat(shard.decisions()).containsExactly(
                new Classification(this.src("IMG_0002.jpg"), "junk", "photo of a screen"),
                new NearDupChosen(this.src("IMG_0003.jpg"), "beach", "sharpest of the burst"),
                new NearDupReject(this.src("IMG_0004.jpg"), "beach", "blurrier than IMG_0003.jpg"));
        assertThat(report).isEqualTo(new CullReport(1, 0, 1200, 340));
    }

    @Test
    void anAllKeepsResponseWritesAnEmptyShardMarkingTheMontageReviewed() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg", "IMG_0002.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" },
                    { "index": 2, "name": "IMG_0002.jpg", "action": "keep" }
                  ]
                }
                """, 800, 90));

        this.culler().cull(prep, OPTIONS);

        final DecisionShard shard = new ShardCodec().read(this.prepDir.resolve("decisions-001.json"));
        assertThat(shard.decisions()).isEmpty();
    }

    @Test
    void cullsEveryMontageSumsTokensAndClosesTheClient() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg");
        this.writeMontage("montage-002", "IMG_0002.jpg");
        this.respondWith(
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

        final CullReport report = this.culler().cull(this.prep("montage-001", "montage-002"), OPTIONS);

        assertThat(this.prepDir.resolve("decisions-001.json")).exists();
        assertThat(this.prepDir.resolve("decisions-002.json")).exists();
        assertThat(report).isEqualTo(new CullReport(2, 0, 2100, 140));
        verify(this.client).close();
    }

    @Test
    void progressCallbackTicksOnceForEachMontageAgainstTheFinalMontageCount() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg");
        this.writeMontage("montage-002", "IMG_0002.jpg");
        this.respondWith(
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

        final List<String> ticks = new ArrayList<>();
        this.culler().cull(this.prep("montage-001", "montage-002"), OPTIONS, (current, total) -> ticks.add(current +
                "/" + total));

        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    @Test
    void cancellationStopsTheLoopLeavingAlreadyWrittenShardsAndAPartialReport() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg");
        this.writeMontage("montage-002", "IMG_0002.jpg");
        this.writeMontage("montage-003", "IMG_0003.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "screenshot" }
                  ]
                }
                """, 100, 10));

        // Cancels once montage-001's tick fires. montage-002/003 are never dispatched, so the
        // client only ever sees one request.
        final var cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final CullReport report = this.culler().cull(
                this.prep("montage-001", "montage-002", "montage-003"), OPTIONS, cancelAfterFirstTick, cancelled::get);

        assertThat(report).isEqualTo(new CullReport(1, 0, 100, 10));
        assertThat(this.prepDir.resolve("decisions-001.json")).exists();
        assertThat(this.prepDir.resolve("decisions-002.json")).doesNotExist();
        assertThat(this.prepDir.resolve("decisions-003.json")).doesNotExist();
        verify(this.messages, times(1)).create(any(MessageCreateParams.class));
        verify(this.client).close();
    }

    // The second, pre-retry check is what actually saves the latency. Without it, a cancellation
    // landing here would still have to wait out a whole extra API round trip before it takes effect.
    @Test
    void cancellationAfterAFailedFirstAttemptSkipsTheRetryAndWritesNothing() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "WRONG.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        // The first poll (the while loop's own entry check) must pass so the doomed first attempt
        // actually runs. The second poll, right before the corrective retry, is where cancellation
        // lands instead.
        final var polls = new AtomicInteger();
        final CancellationSignal cancelBeforeRetry = () -> polls.incrementAndGet() > 1;

        final CullReport report = this.culler().cull(prep, OPTIONS, ProgressCallback.NO_OP, cancelBeforeRetry);

        // The failed first attempt's tokens still count - that call already cost real money.
        assertThat(report).isEqualTo(new CullReport(0, 0, 100, 10));
        assertThat(this.prepDir.resolve("decisions-001.json")).doesNotExist();
        verify(this.messages, times(1)).create(any(MessageCreateParams.class));
    }

    // A stateless call cannot remember the slugs earlier montages picked, so the accumulated
    // cross-shard validation is what enforces group-id uniqueness across the run.
    @Test
    void failsLoudWhenTwoMontagesReuseANearDupGroupId() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg", "IMG_0002.jpg");
        this.writeMontage("montage-002", "IMG_0003.jpg", "IMG_0004.jpg");
        this.respondWith(
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

        assertThatThrownBy(() -> this.culler().cull(this.prep("montage-001", "montage-002"), OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("montage-002")
                .hasMessageContaining("near-dup group 'beach' spans 2 shards");
        assertThat(this.prepDir.resolve("decisions-001.json")).exists();
        assertThat(this.prepDir.resolve("decisions-002.json")).doesNotExist();
    }

    // A file named by both a decision and index.json's unreviewable list would double-move at apply
    // time. That is a real problem, but not one to settle here. The user can answer it with
    // TRUST_DECISION, and that answer lives in a ledger only the apply phase reads. Rejecting the
    // shard here would overrule them, and each rejection costs another paid model call.
    //
    // The fixture is synthetic: the renderer keeps sidecar srcs and unreviewable entries disjoint,
    // so only a hand-edited index.json reaches this state through the model's own verdict.
    @Test
    void writesTheShardWhenAFileIsAlsoListedAsUnreviewableLeavingThatOverlapToApply() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "screenshot" }
                  ]
                }
                """, 1000, 100));

        final CullReport report =
                this.culler().cull(this.prep(List.of(this.src("IMG_0001.jpg")), "montage-001"), OPTIONS);

        assertThat(report).isEqualTo(new CullReport(1, 0, 1000, 100));
        assertThat(new ShardCodec().read(this.prepDir.resolve("decisions-001.json")).decisions())
                .containsExactly(new Classification(this.src("IMG_0001.jpg"), "junk", "screenshot"));
        // One call, so the overlap never even reached the corrective retry.
        verify(this.messages, times(1)).create(any(MessageCreateParams.class));
    }

    // The overlap above is hand-built: the renderer never emits a file as both a sidecar src and an
    // unreviewable entry. A shard read back off disk is the case that needs no such fixture, since
    // nothing constrains what an already-written shard names. So this is the resume-path twin, and
    // the one that would break first if the unreviewable argument were ever restored.
    @Test
    void resumesAnExistingShardNamingAnUnreviewableFileInsteadOfPayingToReCullIt() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg");
        new ShardCodec().write(this.prepDir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(this.src("IMG_0001.jpg"), "junk", "screenshot"))));

        final CullReport report =
                this.culler().cull(this.prep(List.of(this.src("IMG_0001.jpg")), "montage-001"), OPTIONS);

        assertThat(report).isEqualTo(new CullReport(0, 1, 0, 0));
        verify(this.messages, times(0)).create(any(MessageCreateParams.class));
    }

    // A sidecar names the photos its montage shows, so without one there is nothing to key the
    // model's verdicts against and no request can be built. Skipping that montage keeps the rest of
    // the scope culling, and leaves the apply phase to offer the user a corrupt-sidecar remedy.
    @Test
    void skipsAMontageWhoseSidecarIsUnreadableAndStillCullsTheRest() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg");
        this.writeMontage("montage-002", "IMG_0002.jpg");
        Files.writeString(this.prepDir.resolve("montage-001.json"), "{ not json");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0002.jpg", "action": "junk", "reason": "screenshot" }
                  ]
                }
                """, 500, 50));

        final CullReport report = this.culler().cull(this.prep("montage-001", "montage-002"), OPTIONS);

        assertThat(report).isEqualTo(new CullReport(1, 1, 500, 50));
        assertThat(this.prepDir.resolve("decisions-002.json")).exists();
        // Never culled, so no shard is invented for it - and no model call was spent trying.
        assertThat(this.prepDir.resolve("decisions-001.json")).doesNotExist();
        verify(this.messages, times(1)).create(any(MessageCreateParams.class));
    }

    // The state resolveCorruptSidecar() actually leaves behind: the sidecar filed away, its shard
    // still on disk. That shard must survive untouched, because APPLY_ANYWAY is an answer to trust
    // it. Re-culling the montage would overwrite the very decisions the user chose to keep.
    @Test
    void leavesAnExistingShardAloneWhenItsSidecarHasBeenFiledAway() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg");
        new ShardCodec().write(this.prepDir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(this.src("IMG_0001.jpg"), "junk", "the user's own answer"))));
        Files.delete(this.prepDir.resolve("montage-001.json"));

        final CullReport report = this.culler().cull(this.prep("montage-001"), OPTIONS);

        assertThat(report).isEqualTo(new CullReport(0, 1, 0, 0));
        assertThat(new ShardCodec().read(this.prepDir.resolve("decisions-001.json")).decisions())
                .containsExactly(new Classification(this.src("IMG_0001.jpg"), "junk", "the user's own answer"));
        verify(this.messages, times(0)).create(any(MessageCreateParams.class));
    }

    @Test
    void retriesOnceWithTheProblemListWhenTheFirstResponseFailsValidation() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(
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

        final CullReport report = this.culler().cull(prep, OPTIONS);

        final DecisionShard shard = new ShardCodec().read(this.prepDir.resolve("decisions-001.json"));
        assertThat(shard.decisions()).containsExactly(
                new Classification(this.src("IMG_0001.jpg"), "junk", "screenshot"));
        // Both attempts' tokens count: the failed first call cost real money too.
        assertThat(report).isEqualTo(new CullReport(1, 0, 220, 40));
        final var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(this.messages, times(2)).create(captor.capture());
        final MessageCreateParams retry = captor.getAllValues().getLast();
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
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "WRONG.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        assertThatThrownBy(() -> this.culler().cull(prep, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("a corrective retry did not fix it")
                .hasMessageContaining("First attempt (1 problem(s))")
                .hasMessageContaining("Retry (1 problem(s))");
        verify(this.messages, times(2))
                .create(any(MessageCreateParams.class));
        assertThat(this.prepDir.resolve("decisions-001.json")).doesNotExist();
    }

    // A failed attempt must roll its tentative shard back out of the accepted set. Poisoned
    // leftovers would surface as phantom problems when a later montage is validated.
    @Test
    void aFailedFirstAttemptLeavesTheAcceptedSetCleanForLaterMontages() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg");
        this.writeMontage("montage-002", "IMG_0002.jpg");
        this.respondWith(
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

        final CullReport report = this.culler().cull(this.prep("montage-001", "montage-002"), OPTIONS);

        assertThat(new ShardCodec().read(this.prepDir.resolve("decisions-001.json")).decisions())
                .containsExactly(new Classification(this.src("IMG_0001.jpg"), "junk", "blurry"));
        assertThat(new ShardCodec().read(this.prepDir.resolve("decisions-002.json")).decisions())
                .containsExactly(new Classification(this.src("IMG_0002.jpg"), "junk", "screenshot"));
        assertThat(report).isEqualTo(new CullReport(2, 0, 420, 60));
    }

    // The API rejects empty text blocks, so a blank reply cannot be echoed verbatim on retry.
    @Test
    void aBlankResponseRetriesWithAPlaceholderEcho() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(
                response("", 100, 10),
                response("""
                        {
                          "verdicts": [
                            { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                          ]
                        }
                        """, 120, 30));

        final CullReport report = this.culler().cull(prep, OPTIONS);

        assertThat(new ShardCodec().read(this.prepDir.resolve("decisions-001.json")).decisions()).isEmpty();
        assertThat(report).isEqualTo(new CullReport(1, 0, 220, 40));
        final var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(this.messages, times(2)).create(captor.capture());
        final MessageCreateParams retry = captor.getAllValues().getLast();
        assertThat(retry.messages().get(1).content().string().orElseThrow()).isEqualTo("(empty response)");
        assertThat(retry.messages().get(2).content().string().orElseThrow())
                .contains("response carries no text content");
    }

    @Test
    void resumesAMontageWhoseValidShardAlreadyExists() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg");
        this.writeMontage("montage-002", "IMG_0002.jpg");
        new ShardCodec().write(this.prepDir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(this.src("IMG_0001.jpg"), "junk", "photo of a screen"))));
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0002.jpg", "action": "keep" }
                  ]
                }
                """, 500, 50));

        final CullReport report = this.culler().cull(this.prep("montage-001", "montage-002"), OPTIONS);

        assertThat(report).isEqualTo(new CullReport(1, 1, 500, 50));
        final var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(this.messages).create(captor.capture());
        // The one request that went out is montage-002's, still numbered 2 of 2: a skip does not
        // renumber the sheets that follow it.
        assertThat(captor.getValue().messages().getFirst().content().blockParams().orElseThrow()
                .getLast().text().orElseThrow().text()).contains("sheet 002 (2 of 2)");
    }

    @Test
    void reCullsAMontageWhoseExistingShardIsUnreadable() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        Files.writeString(this.prepDir.resolve("decisions-001.json"), "not a shard at all");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "screenshot" }
                  ]
                }
                """, 100, 10));

        final CullReport report = this.culler().cull(prep, OPTIONS);

        assertThat(report).isEqualTo(new CullReport(1, 0, 100, 10));
        final DecisionShard shard = new ShardCodec().read(this.prepDir.resolve("decisions-001.json"));
        assertThat(shard.decisions()).containsExactly(
                new Classification(this.src("IMG_0001.jpg"), "junk", "screenshot"));
    }

    @Test
    void reCullsAMontageWhoseExistingShardBreaksTheContract() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        // Parseable, but a blank reason breaks the shard contract - resume must decline it.
        new ShardCodec().write(this.prepDir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new Classification(this.src("IMG_0001.jpg"), "junk", ""))));
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "junk", "reason": "screenshot" }
                  ]
                }
                """, 100, 10));

        final CullReport report = this.culler().cull(prep, OPTIONS);

        assertThat(report).isEqualTo(new CullReport(1, 0, 100, 10));
        final DecisionShard shard = new ShardCodec().read(this.prepDir.resolve("decisions-001.json"));
        assertThat(shard.decisions()).containsExactly(
                new Classification(this.src("IMG_0001.jpg"), "junk", "screenshot"));
    }

    // A resumed shard joins the accumulated set, so the cross-shard rules keep firing across the
    // resume boundary. A later montage cannot reuse a group id an earlier run's shard claimed.
    @Test
    void aResumedShardStillBlocksALaterGroupIdReuse() throws Exception {
        this.writeMontage("montage-001", "IMG_0001.jpg", "IMG_0002.jpg");
        this.writeMontage("montage-002", "IMG_0003.jpg", "IMG_0004.jpg");
        new ShardCodec().write(this.prepDir.resolve("decisions-001.json"), new DecisionShard("montage-001",
                List.of(new NearDupChosen(this.src("IMG_0001.jpg"), "beach", "sharpest"),
                        new NearDupReject(this.src("IMG_0002.jpg"), "beach", "blurrier"))));
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0003.jpg", "action": "near-dup-chosen", "group": "beach",
                      "chosen_reason": "sharpest" },
                    { "index": 2, "name": "IMG_0004.jpg", "action": "near-dup-reject", "group": "beach",
                      "reason": "blurrier" }
                  ]
                }
                """, 100, 10));

        assertThatThrownBy(() -> this.culler().cull(this.prep("montage-001", "montage-002"), OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("montage-002")
                .hasMessageContaining("near-dup group 'beach' spans 2 shards");
    }

    @Test
    void thinkingIsExplicitlyDisabledByDefault() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        this.culler().cull(prep, OPTIONS);

        final var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(this.messages).create(captor.capture());
        assertThat(captor.getValue().thinking().orElseThrow().isDisabled()).isTrue();
        assertThat(captor.getValue().maxTokens()).isEqualTo(8192);
    }

    @Test
    void configuredThinkingSendsAdaptiveWithAHigherTokenCeiling() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        this.culler(settingsWithThinking()).cull(prep, OPTIONS);

        final var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(this.messages).create(captor.capture());
        assertThat(captor.getValue().thinking().orElseThrow().isAdaptive()).isTrue();
        assertThat(captor.getValue().maxTokens()).isEqualTo(16384);
    }

    @Test
    void sendsSystemPromptMontageImageAndPhotoTable() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        this.culler().cull(prep, OPTIONS);

        final var captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(this.messages).create(captor.capture());
        final MessageCreateParams request = captor.getValue();
        assertThat(request.model().asString()).isEqualTo("claude-sonnet-5");
        assertThat(request.system().orElseThrow().string().orElseThrow())
                .contains("### `junk`")
                .contains("When unsure, keep.");
        final List<ContentBlockParam> blocks =
                request.messages().getFirst().content().blockParams().orElseThrow();
        final String imageData = blocks.getFirst().image().orElseThrow()
                .source().base64().orElseThrow().data();
        assertThat(imageData).isEqualTo(
                Base64.getEncoder().encodeToString(Files.readAllBytes(this.prepDir.resolve("montage-001.jpg"))));
        assertThat(blocks.getLast().text().orElseThrow().text())
                .contains("Scope: 2019-06")
                .contains("1. IMG_0001.jpg");
        assertThat(request.outputConfig()).isPresent();
    }

    @Test
    void failsLoudWhenAVerdictNamesTheWrongPhoto() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "WRONG.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        assertThatThrownBy(() -> this.culler().cull(prep, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("verdict 1 names 'WRONG.jpg' but photo 1 is 'IMG_0001.jpg'");
        assertThat(this.prepDir.resolve("decisions-001.json")).doesNotExist();
    }

    @Test
    void failsLoudWhenAVerdictIsMissing() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg", "IMG_0002.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "keep" }
                  ]
                }
                """, 100, 10));

        assertThatThrownBy(() -> this.culler().cull(prep, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("no verdict for photo 2 (IMG_0002.jpg)");
        assertThat(this.prepDir.resolve("decisions-001.json")).doesNotExist();
    }

    @Test
    void failsLoudWhenAnActionIsNotAConfiguredCategory() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(response("""
                {
                  "verdicts": [
                    { "index": 1, "name": "IMG_0001.jpg", "action": "trash", "reason": "blurry" }
                  ]
                }
                """, 100, 10));

        assertThatThrownBy(() -> this.culler().cull(prep, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("invalid action 'trash'");
        assertThat(this.prepDir.resolve("decisions-001.json")).doesNotExist();
    }

    @Test
    void failsLoudWhenTheResponseIsNotTheVerdictJson() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        this.respondWith(response("not json at all", 100, 10));

        assertThatThrownBy(() -> this.culler().cull(prep, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessageContaining("montage-001")
                .hasMessageContaining("not valid verdict JSON");
    }

    @Test
    void failsLoudWhenTheModelIsNotConfigured() throws Exception {
        final PrepDir prep = this.prepWithOneMontage("IMG_0001.jpg");
        final var culler = new AnthropicCuller(cullerPrompt(settings(null)), new ShardCodec(),
                new SidecarReader(), settings(null),
                () -> {
                    throw new AssertionError("client must not be built without a model");
                });

        assertThatThrownBy(() -> culler.cull(prep, OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sluice.cull.provider-settings.model");
    }

    // Two routes lead to a stored key, and someone hitting this has taken neither. The message
    // names both rather than the one the app happens to check first.
    @Test
    void failsLoudNamingBothRoutesToAKeyWhenNoTierHoldsOne() {
        final SecretStore empty = new FixedSecretStore(null);

        assertThatThrownBy(() -> AnthropicCuller.defaultClient(
                settings("claude-sonnet-5").providerSettings(), empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Settings")
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    // Nothing observes the key after the client is built. What this proves is that the store's
    // answer was carried through rather than dropped, since dropping it reaches the no-key refusal.
    @Test
    void buildsTheClientFromTheKeyTheStoreHolds() {
        assertThatCode(() -> AnthropicCuller.defaultClient(settings("claude-sonnet-5").providerSettings(),
                new FixedSecretStore("sk-synthetic-0001"))).doesNotThrowAnyException();
    }

    // The provider half becomes the credential's filename, and a settings screen saves under the
    // same id this culler reads back. Two separate literals agreeing today is not the same as them
    // being tied together.
    @Test
    void namesItsCredentialAfterTheProviderItRegistersAs() {
        assertThat(AnthropicCuller.API_KEY.provider()).isEqualTo(this.culler().id());
    }

    private AnthropicCuller culler() {
        return this.culler(settings("claude-sonnet-5"));
    }

    private AnthropicCuller culler(final CullSettings settings) {
        when(this.client.messages()).thenReturn(this.messages);
        return new AnthropicCuller(cullerPrompt(settings), new ShardCodec(), new SidecarReader(),
                settings, () -> this.client);
    }

    private void respondWith(final Message first, final Message... rest) {
        when(this.messages.create(any(MessageCreateParams.class))).thenReturn(first, rest);
    }

    private static Message response(final String json, final long inputTokens, final long outputTokens) {
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

    private PrepDir prepWithOneMontage(final String... names) throws IOException {
        this.writeMontage("montage-001", names);
        return this.prep("montage-001");
    }

    // One montage whose sidecar lists the given photos, plus its montage JPEG (any bytes do: the
    // culler only reads and encodes them).
    private void writeMontage(final String montage, final String... names) throws IOException {
        final var photos = new StringBuilder();
        for (final String name : names) {
            if (!photos.isEmpty()) {
                photos.append(",\n");
            }
            photos.append("""
                    { "src": "%s", "name": "%s", "time": "2019-06-20T15:00:10Z", "received": false }"""
                    .formatted(jsonEscaped(this.src(name)), name));
        }
        Files.writeString(this.prepDir.resolve(montage + ".json"), """
                {
                  "montage": "%s",
                  "photos": [ %s ]
                }
                """.formatted(montage, photos));
        Files.write(this.prepDir.resolve(montage + ".jpg"), new byte[] {1, 2, 3, 4});
    }

    private PrepDir prep(final String... montages) {
        return this.prep(List.of(), montages);
    }

    private PrepDir prep(final List<Path> unreviewable, final String... montages) {
        return new PrepDir("2019-06", List.of("junk"), this.prepDir.resolve("base"), 0, unreviewable, montages.length,
                this.prepDir, List.of(montages));
    }

    private Path src(final String name) {
        return this.prepDir.resolve("sorted").resolve(name);
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static CullerPrompt cullerPrompt(final CullSettings settings) {
        return new CullerPrompt(settings);
    }

    private static CullSettings settings(final @Nullable String model) {
        return new FixedSettings("anthropic", CARDS,
                new CullProviderSettings(model, null, null, null));
    }

    private static CullSettings settingsWithThinking() {
        return new FixedSettings("anthropic", CARDS,
                new CullProviderSettings("claude-sonnet-5", null, true, null));
    }

    private record FixedSettings(String provider, List<CullCategory> categories,
                                 CullProviderSettings providerSettings) implements CullSettings {

        @Override
        public ExternalAgentSettings externalAgent() {
            return new ExternalAgentSettings(WatchMode.MANUAL);
        }

        @Override
        public MontageConfig montage() {
            return MontageConfig.defaults();
        }
    }

    // Stands in for whatever this machine's tiers hold, a credential or nothing. Building a client
    // neither stores nor clears one, so those two refuse rather than pretending to work.
    private record FixedSecretStore(@Nullable String held) implements SecretStore {

        @Override
        public Optional<String> secret(final SecretId id) {
            return Optional.ofNullable(this.held);
        }

        @Override
        public SecretStatus status(final SecretId id) {
            return this.held == null ? new SecretStatus.Absent() : new SecretStatus.InFile();
        }

        @Override
        public void save(final SecretId id, final String secret) {
            throw new UnsupportedOperationException("building a client stores no credential");
        }

        @Override
        public void remove(final SecretId id) {
            throw new UnsupportedOperationException("building a client clears no credential");
        }
    }
}
