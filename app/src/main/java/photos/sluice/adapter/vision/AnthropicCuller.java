package photos.sluice.adapter.vision;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ValidationReport;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The {@link VisionCuller} provider that calls the user's configured Anthropic vision model from
 * inside the app. Each montage is one stateless request: the shared system prompt, the montage
 * JPEG, and the photo table. A structured-output schema constrains the response to a JSON verdict
 * list. The model must return a verdict for every tile. That forces a look at every photo instead
 * of skimming past faint junk, and gives a hard completeness check. Keep verdicts are stripped
 * before the shard is written, so the on-disk contract stays the external-agent one: non-keep
 * decisions only.
 *
 * <p>The model references photos by tile index and filename, never by path. The response is
 * resolved index to sidecar src here. A name that does not match the sidecar entry at that index
 * fails validation rather than healing. The mismatch signals a mis-keyed tile, and guessing which
 * field to trust could set aside the wrong photo.
 *
 * <p>Failure channels split by who can fix them. A response that fails validation is a content
 * problem the model itself can often fix. It gets one corrective retry that echoes the failed
 * reply back with the full problem list. A second failure throws checked {@link CullException}
 * naming the montage and both attempts' problems. The one-retry cap is deliberate, so a model
 * that cannot cull a montage stops burning tokens. Missing connection settings (model id, API
 * key) are the user's configuration to fix, and fail unchecked with the property or variable
 * name. A montage image that can't be read is broken app output, and fails unchecked because the
 * scope needs re-prepping. An unreadable sidecar skips its own montage instead, leaving the apply
 * phase to offer the user a repair - see {@link #readEntries}.
 *
 * <p>A montage whose valid shard is already on disk is skipped. A run can be interrupted by a
 * crash or a user cancellation. Either way, re-invoking it on the same prep directory finishes
 * only the remainder. An invalid existing shard is re-culled and overwritten. A stray decisions
 * file naming no current montage is left untouched. {@link CullOptions} is not wired yet. Every
 * montage needs a shard regardless of allowPartial, and timeout is unhonored.
 *
 * <p>The API client is built lazily inside {@link #cull}, never at startup, so the app boots
 * without an API key for users on other providers. The factory seam exists for tests to inject a
 * mock client.
 */
@Component
class AnthropicCuller implements VisionCuller {

    // The response ceiling, which doubles as a per-call cost cap. Sizing: a verdict runs about
    // 70 tokens, so the largest list a sheet can produce is ~3.5k for a dense 7x7 grid. 8192
    // holds that worst case more than twice over.
    private static final long MAX_TOKENS = 8192;
    // A thinking run adds a reasoning allowance equal to the whole answer ceiling. Reasoning
    // shares the response budget. The extra 8192 (~300 tokens of deliberation per photo on a
    // full default 5x5 sheet) keeps even a long chain from squeezing out the verdict JSON.
    private static final long MAX_TOKENS_THINKING = 16384;
    private static final int DEFAULT_TRANSPORT_RETRIES = 2;
    private static final String KEEP = "keep";
    private static final String NEAR_DUP_CHOSEN = "near-dup-chosen";
    private static final String NEAR_DUP_REJECT = "near-dup-reject";

    // Deliberately flat rather than a discriminated union on action. The verdict fields are simple
    // enough that ShardValidator catches per-action gaps (a missing reason, say) with better
    // messages than a schema violation would produce.
    private static final JsonOutputFormat.Schema RESPONSE_SCHEMA = schemaOf(Map.of(
            "type", "object",
            "properties", Map.of(
                    "verdicts", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "index", Map.of("type", "integer"),
                                            "name", Map.of("type", "string"),
                                            "action", Map.of("type", "string"),
                                            "reason", Map.of("type", "string"),
                                            "group", Map.of("type", "string"),
                                            "chosen_reason", Map.of("type", "string")),
                                    "required", List.of("index", "name", "action"),
                                    "additionalProperties", false))),
            "required", List.of("verdicts"),
            "additionalProperties", false));

    private final CullerPrompt prompt;
    private final ShardCodec shardCodec;
    private final SidecarReader sidecarReader;
    private final CullSettings settings;
    private final Supplier<AnthropicClient> clientFactory;
    private final ShardValidator validator = new ShardValidator();
    private final JsonMapper mapper =
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /**
     * Constructs the culler with the default client factory.
     *
     * @param prompt {@link CullerPrompt} the prompt builder
     * @param shardCodec {@link ShardCodec} reads and writes per-montage shards
     * @param sidecarReader {@link SidecarReader} reads per-montage sidecars
     * @param settings {@link CullSettings} the cull settings
     */
    @Autowired
    AnthropicCuller(final CullerPrompt prompt, final ShardCodec shardCodec, final SidecarReader sidecarReader,
                    final CullSettings settings) {
        this(prompt, shardCodec, sidecarReader, settings,
                () -> defaultClient(settings.providerSettings()));
    }

    /**
     * Constructs the culler with an injectable client factory, for tests.
     *
     * @param prompt {@link CullerPrompt} the prompt builder
     * @param shardCodec {@link ShardCodec} reads and writes per-montage shards
     * @param sidecarReader {@link SidecarReader} reads per-montage sidecars
     * @param settings {@link CullSettings} the cull settings
     * @param clientFactory a {@link Supplier} of {@link AnthropicClient}, builds the Anthropic client used to call
     * the model
     */
    AnthropicCuller(final CullerPrompt prompt, final ShardCodec shardCodec, final SidecarReader sidecarReader,
                    final CullSettings settings, final Supplier<AnthropicClient> clientFactory) {
        this.prompt = prompt;
        this.shardCodec = shardCodec;
        this.sidecarReader = sidecarReader;
        this.settings = settings;
        this.clientFactory = clientFactory;
    }

    /**
     * The model's verdict for one tile, as the response schema shapes it. Which fields a verdict
     * needs depends on its action. Absent ones become empty strings, so {@link ShardValidator}
     * reports them aggregated instead of one parse crash per gap.
     */
    private record RawVerdict(@Nullable Integer index, @Nullable String name, @Nullable String action,
                              @Nullable String reason, @Nullable String group,
                              @JsonProperty("chosen_reason") @Nullable String chosenReason) {
    }

    /**
     * The full parsed response body: the list of per-tile verdicts the model returned.
     */
    private record RawResponse(@Nullable List<@Nullable RawVerdict> verdicts) {
    }

    /**
     * One attempt's result: the accepted shard, or the problems that rejected it, never both.
     */
    private record AttemptOutcome(@Nullable DecisionShard shard, List<String> problems) {
    }

    /**
     * Returns this provider's identifier.
     *
     * @return {@link String} the provider id, "anthropic"
     */
    @Override
    public String id() {
        return "anthropic";
    }

    /**
     * Culls the prep directory's montages with no progress reporting.
     *
     * @param prep {@link PrepDir} the prep directory to cull
     * @param opts {@link CullOptions} cull options
     * @return {@link CullReport} the cull report
     * @throws CullException if culling fails
     */
    @Override
    public CullReport cull(final PrepDir prep, final CullOptions opts) throws CullException {
        return this.cull(prep, opts, ProgressCallback.NO_OP);
    }

    /**
     * Culls the prep directory's montages with no cancellation support.
     *
     * @param prep {@link PrepDir} the prep directory to cull
     * @param opts {@link CullOptions} cull options
     * @param progress {@link ProgressCallback} progress callback ticked per montage
     * @return {@link CullReport} the cull report
     * @throws CullException if culling fails
     */
    @Override
    public CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress) throws CullException {
        return this.cull(prep, opts, progress, CancellationSignal.NEVER);
    }

    /**
     * Culls every montage in the prep directory by calling the configured Anthropic vision model,
     * resuming from any already-valid shards and honoring cancellation between montages.
     *
     * @param prep {@link PrepDir} the prep directory to cull
     * @param opts {@link CullOptions} cull options
     * @param progress {@link ProgressCallback} progress callback ticked per montage
     * @param cancellation {@link CancellationSignal} signal checked between montages to stop early
     * @return {@link CullReport} the cull report
     * @throws CullException if a montage's response fails validation and the corrective retry does too
     */
    @Override
    public CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress,
                           final CancellationSignal cancellation)
            throws CullException {
        final String model = this.requiredModel();
        final boolean thinking = Boolean.TRUE.equals(this.settings.providerSettings().thinking());
        final String systemPrompt = this.prompt.systemPrompt();
        final List<String> categoryNames = this.settings.categories().stream().map(CullCategory::name).toList();
        long inputTokens = 0;
        long outputTokens = 0;
        int culled = 0;
        int skipped = 0;
        final int total = prep.entries().size();
        int ordinal = 0;
        // Every readable sidecar is read up front, so validation sees as much of the scope as
        // survives. That is the in-scope set the shard contract defines. Accepted shards still
        // accumulate one montage at a time. ShardValidator's cross-shard rules (a near-dup group
        // id reused by two montages, say) can only fire on the whole set. Earlier shards are known
        // clean, so any fresh problem implicates the current montage.
        //
        // A sidecar that can't be read leaves its montage out of the map entirely. The loop below
        // then skips that montage instead of failing the whole run. See readEntries().
        final var entriesByMontage = new LinkedHashMap<String, List<SidecarPhotoEntry>>();
        for (final String montage : prep.entries()) {
            this.readEntries(prep.prepDir().resolve(montage + ".json"))
                    .ifPresent(entries -> entriesByMontage.put(montage, entries));
        }
        final List<Path> scopeSrcs = entriesByMontage.values().stream()
                .flatMap(List::stream)
                .map(SidecarPhotoEntry::src)
                .toList();
        final var acceptedShards = new ArrayList<ShardFile>();
        final AnthropicClient client = this.clientFactory.get();
        try {
            // A second check runs below, right before the corrective retry. That halves the
            // worst-case cancel latency, at the cost of discarding a paid-for first-attempt
            // response when a cancel lands between it and the retry. Either way, an interrupted
            // montage writes no shard and the loop ends without throwing.
            while (ordinal < total && !cancellation.isCancelled()) {
                final String montage = prep.entries().get(ordinal);
                ordinal++;
                final List<SidecarPhotoEntry> entries = entriesByMontage.get(montage);
                final Path shardPath = prep.prepDir().resolve(MontageNaming.shardFileFor(montage));
                if (entries == null || this.resumesExistingShard(shardPath, montage, acceptedShards,
                        scopeSrcs, categoryNames)) {
                    skipped++;
                } else {
                    final MessageCreateParams request = request(model, thinking, systemPrompt,
                            this.prompt.userTurn(prep.scope(), montage, ordinal, total, entries),
                            montageImageBase64(prep.prepDir(), montage));
                    final Message response = client.messages().create(request);
                    inputTokens += response.usage().inputTokens();
                    outputTokens += response.usage().outputTokens();
                    AttemptOutcome outcome = this.attempt(montage, entries, response, acceptedShards,
                            scopeSrcs, categoryNames);
                    if (outcome.shard() == null && !cancellation.isCancelled()) {
                        final Message retryResponse = client.messages().create(retryRequest(request,
                                responseText(response), this.prompt.correctionTurn(outcome.problems())));
                        inputTokens += retryResponse.usage().inputTokens();
                        outputTokens += retryResponse.usage().outputTokens();
                        final AttemptOutcome retried = this.attempt(montage, entries, retryResponse,
                                acceptedShards, scopeSrcs, categoryNames);
                        if (retried.shard() == null) {
                            // A cancellation requested while the retry call itself was in flight
                            // reaches here too. Thrown only when uncancelled, so a cancel never
                            // surfaces as a retry-failure CullException, matching the montage-loop
                            // check above.
                            if (!cancellation.isCancelled()) {
                                throw retryFailedException(prep.scope(), montage, outcome.problems(),
                                        retried.problems());
                            }
                        } else {
                            outcome = retried;
                        }
                    }
                    if (outcome.shard() != null) {
                        this.shardCodec.write(shardPath, outcome.shard());
                        culled++;
                    }
                }
                progress.tick(ordinal, total);
            }
        } finally {
            client.close();
        }
        return new CullReport(culled, skipped, inputTokens, outputTokens);
    }

    /**
     * Reads one montage's sidecar, translating an unreadable one into the empty case rather than
     * failing the run. A sidecar names the photos its montage's tile grid shows. Without one there
     * is nothing to key the model's verdicts back to files, so that montage cannot be culled.
     *
     * <p>Skipping it beats failing the run, because a montage that already holds a shard has a
     * genuine repair path. The apply phase reports it as a corrupt sidecar and offers the choice of
     * trusting that shard or setting the montage aside. Failing here would put the run out of reach
     * of the answer to that question.
     *
     * <p>Damaged content and a read that merely failed are both tolerated, matching what the apply
     * phase does with the same sidecar. Neither is a judgement this class is placed to make.
     *
     * @param sidecarPath {@link Path} path of the montage's sidecar
     * @return an {@link Optional} {@link List} of {@link SidecarPhotoEntry} the entries, or empty if unreadable
     */
    private Optional<List<SidecarPhotoEntry>> readEntries(final Path sidecarPath) {
        try {
            return Optional.of(this.sidecarReader.readEntries(sidecarPath));
        } catch (final UncheckedIOException e) {
            return Optional.empty();
        }
    }

    /**
     * Skips a montage whose existing shard still passes the full contract against everything
     * accepted so far - the resume path for an interrupted run. An unreadable or contract-breaking
     * shard is not an error here. It reports false, and the caller re-culls the montage, the fresh
     * shard overwriting the bad one.
     *
     * @param shardPath {@link Path} path of the montage's shard file
     * @param montage {@link String} the montage name
     * @param acceptedShards a {@link List} of {@link ShardFile}, shards accepted so far, mutated on acceptance
     * @param scopeSrcs a {@link List} of {@link Path}, every in-scope source path for the run
     * @param categoryNames a {@link List} of {@link String}, configured cull category names
     * @return boolean true if the existing shard is valid and was accepted
     */
    private boolean resumesExistingShard(final Path shardPath, final String montage,
                                         final List<ShardFile> acceptedShards, final List<Path> scopeSrcs,
                                         final List<String> categoryNames) {
        if (!Files.exists(shardPath)) {
            return false;
        }
        final DecisionShard existing;
        try {
            existing = this.shardCodec.read(shardPath);
        } catch (final UncheckedIOException e) {
            return false;
        }
        return this.acceptIfValid(montage, existing, acceptedShards, scopeSrcs, categoryNames).isEmpty();
    }

    /**
     * One response's full journey: response text -> candidate shard -> accumulated validation.
     *
     * @param montage {@link String} the montage name
     * @param entries a {@link List} of {@link SidecarPhotoEntry}, the montage's sidecar photo entries
     * @param response {@link Message} the model's response to validate
     * @param acceptedShards a {@link List} of {@link ShardFile}, shards accepted so far
     * @param scopeSrcs a {@link List} of {@link Path}, every in-scope source path for the run
     * @param categoryNames a {@link List} of {@link String}, configured cull category names
     * @return {@link AttemptOutcome} the resulting shard, or the problems found
     */
    private AttemptOutcome attempt(final String montage, final List<SidecarPhotoEntry> entries, final Message response,
                                   final List<ShardFile> acceptedShards, final List<Path> scopeSrcs,
                                   final List<String> categoryNames) {
        final var problems = new ArrayList<String>();
        final DecisionShard shard = this.shardOf(montage, entries, response, problems);
        if (shard == null) {
            return new AttemptOutcome(null, problems);
        }
        final List<String> validationProblems =
                this.acceptIfValid(montage, shard, acceptedShards, scopeSrcs, categoryNames);
        if (!validationProblems.isEmpty()) {
            return new AttemptOutcome(null, validationProblems);
        }
        return new AttemptOutcome(shard, List.of());
    }

    /**
     * Tentatively adds the shard to the accepted set and validates the whole set against the
     * scope's full src list. A clean result keeps the shard and returns no problems. Anything else
     * rolls the addition back, so a retry or re-cull starts from the same accepted state.
     *
     * <p>index.json's own unreviewable list is deliberately left out of the check. It is prep-dir
     * state a user's troubleshooting answer can overrule, and only the apply phase reads those
     * answers. Checking it here would reject a shard on a verdict the user has already settled, and
     * every rejection costs another paid model call.
     *
     * <p>A verdict this class builds cannot name one of those files anyway. Each comes from a
     * sidecar entry, and the montage renderer keeps the reviewable and unreviewable sets disjoint.
     * A shard read back off disk carries no such guarantee, which is a second reason the question
     * belongs to the apply phase rather than here.
     *
     * @param montage {@link String} the montage name
     * @param shard {@link DecisionShard} the candidate shard to validate
     * @param acceptedShards a {@link List} of {@link ShardFile}, shards accepted so far, mutated by this call
     * @param scopeSrcs a {@link List} of {@link Path}, every in-scope source path for the run
     * @param categoryNames a {@link List} of {@link String}, configured cull category names
     * @return a {@link List} of {@link String}, validation problems found, empty if the shard was accepted
     */
    private List<String> acceptIfValid(final String montage, final DecisionShard shard,
                                       final List<ShardFile> acceptedShards, final List<Path> scopeSrcs,
                                       final List<String> categoryNames) {
        acceptedShards.add(new ShardFile(montage, shard));
        final ValidationReport report = this.validator.validate(acceptedShards, scopeSrcs, categoryNames, List.of());
        if (!report.valid()) {
            acceptedShards.removeLast();
        }
        return report.findings().stream().map(Finding::describe).toList();
    }

    /**
     * Turns one montage's response into a candidate shard, or reports every response-level problem
     * found and returns null. Only what exists solely in the response is checked here: tile-index
     * coverage and the name match. Everything shard-shaped stays with ShardValidator, the
     * contract's single source of truth, which the caller runs over the accumulated set.
     *
     * @param montage {@link String} the montage name
     * @param entries a {@link List} of {@link SidecarPhotoEntry}, the montage's sidecar photo entries
     * @param response {@link Message} the model's response to parse
     * @param problems a {@link List} of {@link String}, accumulator for problems found, mutated by this call
     * @return {@link DecisionShard} the resulting shard, or null if problems were found
     */
    private @Nullable DecisionShard shardOf(final String montage, final List<SidecarPhotoEntry> entries,
                                            final Message response, final List<String> problems) {
        final RawResponse parsed = this.parse(response, problems);
        if (parsed == null) {
            return null;
        }
        final var decisions = new ArrayList<Decision>();
        final var seenIndices = new HashSet<Integer>();
        final List<@Nullable RawVerdict> verdicts = parsed.verdicts() == null ? List.of() : parsed.verdicts();
        for (final RawVerdict verdict : verdicts) {
            collectDecision(verdict, entries, seenIndices, decisions, problems);
        }
        for (int index = 1; index <= entries.size(); index++) {
            if (!seenIndices.contains(index)) {
                problems.add("no verdict for photo " + index + " (" + entries.get(index - 1).name() + ")");
            }
        }
        if (!problems.isEmpty()) {
            return null;
        }
        return new DecisionShard(montage, decisions);
    }

    /**
     * Checks one verdict's response-level contract: index in range and unseen, name matching the
     * sidecar entry at that index. When the contract holds, the non-keep decision it maps to is
     * collected.
     *
     * @param verdict {@link RawVerdict} the raw verdict to check
     * @param entries a {@link List} of {@link SidecarPhotoEntry}, the montage's sidecar photo entries
     * @param seenIndices a {@link HashSet} of {@link Integer}, indices already claimed by a verdict, mutated by this
     * call
     * @param decisions a {@link List} of {@link Decision}, accumulator for collected decisions, mutated by this call
     * @param problems a {@link List} of {@link String}, accumulator for problems found, mutated by this call
     */
    private static void collectDecision(final @Nullable RawVerdict verdict, final List<SidecarPhotoEntry> entries,
                                        final HashSet<Integer> seenIndices, final List<Decision> decisions,
                                        final List<String> problems) {
        if (verdict == null) {
            problems.add("null verdict entry");
            return;
        }
        final Integer index = verdict.index();
        if (index == null || index < 1 || index > entries.size()) {
            problems.add("verdict index " + index + " out of range 1.." + entries.size());
            return;
        }
        if (!seenIndices.add(index)) {
            problems.add("photo " + index + " has more than one verdict");
            return;
        }
        final SidecarPhotoEntry entry = entries.get(index - 1);
        if (!entry.name().equals(verdict.name())) {
            problems.add("verdict " + index + " names '" + verdict.name()
                    + "' but photo " + index + " is '" + entry.name() + "'");
            return;
        }
        final String action = orEmpty(verdict.action());
        if (action.equals(KEEP)) {
            return;
        }
        decisions.add(switch (action) {
            case NEAR_DUP_CHOSEN -> new NearDupChosen(entry.src(), orEmpty(verdict.group()),
                    orEmpty(verdict.chosenReason()));
            case NEAR_DUP_REJECT -> new NearDupReject(entry.src(), orEmpty(verdict.group()),
                    orEmpty(verdict.reason()));
            default -> new Classification(entry.src(), action, orEmpty(verdict.reason()));
        });
    }

    /**
     * Parses the response text into a raw verdict list, recording a problem if it can't be parsed.
     *
     * @param response {@link Message} the model's response to parse
     * @param problems a {@link List} of {@link String}, accumulator for problems found, mutated by this call
     * @return {@link RawResponse} the parsed response, or null if it couldn't be parsed
     */
    private @Nullable RawResponse parse(final Message response, final List<String> problems) {
        final String text = responseText(response);
        if (text.isBlank()) {
            problems.add("response carries no text content");
            return null;
        }
        try {
            final RawResponse parsed = this.mapper.readValue(text, RawResponse.class);
            // A literal null document deserializes to null; the IDE binds the generic result to
            // the non-null type and can't see that.
            //noinspection ConstantValue
            if (parsed == null) {
                problems.add("response is not a JSON object");
            }
            return parsed;
        } catch (final JacksonException e) {
            problems.add("response is not valid verdict JSON: " + e.getMessage());
            return null;
        }
    }

    /**
     * Concatenates every text block in the response.
     *
     * @param response {@link Message} the model's response
     * @return {@link String} the response's full text content
     */
    private static String responseText(final Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }

    /**
     * Builds the exception thrown when a montage's corrective retry still fails validation.
     *
     * @param scope {@link String} the cull scope
     * @param montage {@link String} the montage that failed
     * @param firstProblems a {@link List} of {@link String}, problems from the first attempt
     * @param retryProblems a {@link List} of {@link String}, problems from the retry attempt
     * @return {@link CullException} the exception naming both attempts' problems
     */
    private static CullException retryFailedException(final String scope, final String montage,
                                                      final List<String> firstProblems,
                                                      final List<String> retryProblems) {
        return new CullException("Cull for " + scope + " failed at " + montage
                + " and a corrective retry did not fix it."
                + "\nFirst attempt (" + firstProblems.size() + " problem(s)):\n - "
                + String.join("\n - ", firstProblems)
                + "\nRetry (" + retryProblems.size() + " problem(s)):\n - "
                + String.join("\n - ", retryProblems));
    }

    /**
     * Assembles one montage's complete API request. The image block precedes the text turn per
     * Anthropic's vision guidance: models resolve references into an image better when the image
     * comes first. The schema rides along as a structured-output format, so the response text is
     * the verdict JSON itself, never prose around it. No sampling parameters - current Anthropic
     * models reject them outright. Thinking is always sent explicitly, never left to the model
     * generation's own default: disabled unless configured on, adaptive when it is.
     *
     * @param model {@link String} the model id to request
     * @param thinking boolean whether to enable adaptive thinking
     * @param systemPrompt {@link String} the shared system prompt
     * @param userTurn {@link String} the montage's user turn text
     * @param imageBase64 {@link String} the montage image, base64-encoded
     * @return {@link MessageCreateParams} the assembled request
     */
    private static MessageCreateParams request(final String model, final boolean thinking, final String systemPrompt,
                                               final String userTurn, final String imageBase64) {
        final var builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(thinking ? MAX_TOKENS_THINKING : MAX_TOKENS)
                .system(systemPrompt)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(ImageBlockParam.builder()
                                .source(Base64ImageSource.builder()
                                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                        .data(imageBase64)
                                        .build())
                                .build()),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(userTurn).build())))
                .outputConfig(OutputConfig.builder()
                        .format(JsonOutputFormat.builder()
                                .schema(RESPONSE_SCHEMA)
                                .build())
                        .build());
        if (thinking) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        } else {
            builder.thinking(ThinkingConfigDisabled.builder().build());
        }
        return builder.build();
    }

    /**
     * The corrective retry replays the failed exchange on top of the original request. The model's
     * own reply comes back as an assistant turn, then the problem list asks for corrected JSON.
     * A blank reply is echoed as a placeholder, since the API rejects empty text blocks.
     *
     * @param request {@link MessageCreateParams} the original request to replay
     * @param responseText {@link String} the model's failed response text
     * @param correctionTurn {@link String} the follow-up turn listing the problems
     * @return {@link MessageCreateParams} the request built for the retry call
     */
    private static MessageCreateParams retryRequest(final MessageCreateParams request, final String responseText,
                                                    final String correctionTurn) {
        return request.toBuilder()
                .addAssistantMessage(responseText.isBlank() ? "(empty response)" : responseText)
                .addUserMessage(correctionTurn)
                .build();
    }

    /**
     * Builds a structured-output schema from a plain map representation.
     *
     * @param schema a {@link Map} of {@link String} to {@link Object}, the schema, as a plain nested map
     * @return {@link JsonOutputFormat.Schema} the built schema
     */
    private static JsonOutputFormat.Schema schemaOf(final Map<String, Object> schema) {
        final var builder = JsonOutputFormat.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }

    /**
     * The montage image is this app's own prep output, so an unreadable one means the prep
     * directory is broken. That fails loud and unchecked, same as an unreadable sidecar.
     *
     * @param prepDir {@link Path} the prep directory
     * @param montage {@link String} the montage name
     * @return {@link String} the montage's JPEG image, base64-encoded
     */
    private static String montageImageBase64(final Path prepDir, final String montage) {
        final Path imagePath = prepDir.resolve(montage + ".jpg");
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read montage image " + imagePath, e);
        }
    }

    /**
     * Reads the configured model id, failing loud if it isn't set.
     *
     * @return {@link String} the configured model id
     */
    private String requiredModel() {
        final String model = this.settings.providerSettings().model();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("sluice.cull.provider-settings.model is not set; "
                    + "the 'anthropic' vision provider needs the model id to request");
        }
        return model;
    }

    /**
     * Package-private so the live verify can wrap the client this builds instead of its own.
     *
     * @param providerSettings {@link CullProviderSettings} the configured Anthropic provider settings
     * @return {@link AnthropicClient} the built Anthropic client
     */
    static AnthropicClient defaultClient(final CullProviderSettings providerSettings) {
        final String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Environment variable ANTHROPIC_API_KEY is not set; "
                    + "the 'anthropic' vision provider needs it to call the API");
        }
        final var builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
        final String endpoint = providerSettings.endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.baseUrl(endpoint);
        }
        // Transport failures (429/5xx/timeouts) are the SDK's own retry family: exponential
        // backoff honoring retry-after, separate from the one content retry above.
        final Integer maxRetries = providerSettings.maxRetries();
        builder.maxRetries(maxRetries == null ? DEFAULT_TRANSPORT_RETRIES : maxRetries);
        return builder.build();
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
