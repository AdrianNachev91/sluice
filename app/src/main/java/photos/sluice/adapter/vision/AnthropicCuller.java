package photos.sluice.adapter.vision;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.models.ModelInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.ModelOption;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.SpendCeiling;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.application.port.out.VisionProviderDescriptor;
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
import photos.sluice.domain.cull.Verdict;
import photos.sluice.domain.cull.Verdict.Keep;
import photos.sluice.domain.cull.VerdictAction;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.secrets.SecretId;
import photos.sluice.secrets.SecretStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The {@link VisionCuller} provider that calls the user's configured Anthropic vision model from
 * inside the app. Each montage is one stateless request: the shared system prompt, the montage
 * JPEG, and the photo table. A structured-output schema constrains the response to a JSON verdict
 * list. The model must return a verdict for every tile. That forces a look at every photo rather
 * than a skim over the ones that need one. Every verdict is written to the shard, keeps included,
 * which is the same on-disk contract an external agent answers.
 *
 * <p>The model references photos by tile index and filename, never by path. The response is
 * resolved index to sidecar src here. A name that does not match the sidecar entry at that index
 * fails validation rather than healing, since guessing which field to trust could set aside the
 * wrong photo.
 *
 * <p>A response that fails validation gets one corrective retry echoing the failed reply back with
 * the problem list. A second failure throws checked {@link CullException}. That cap is deliberate,
 * so a model that cannot cull a montage stops burning tokens. The design doc's failure-channel
 * table has the rest, split by who can fix each one.
 *
 * <p>A montage whose valid shard is already on disk is skipped, so re-invoking an interrupted run
 * on the same prep directory finishes only the remainder. An invalid existing shard is re-culled
 * and overwritten. A stray decisions file naming no current montage is left untouched.
 * {@link CullOptions} is not wired yet. Every montage needs a shard regardless of allowPartial,
 * and timeout is unhonored.
 *
 * <p>An API client is built lazily inside whichever call needs one, never at startup, so the app
 * boots without an API key for users on other providers.
 *
 * <p>Flowcharts, failure channels and scenario table:
 * {@code app/docs/design/adapter/vision/anthropic-culler.md}.
 */
@Component
class AnthropicCuller implements VisionCuller {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCuller.class);

    static final String PROVIDER_ID = "anthropic";
    // What the SDK client reaches when the endpoint field is left blank, per Anthropic's own docs.
    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com";
    // The console's front door rather than the keys page itself, since a deep link into somebody
    // else's web app is the part that moves.
    private static final String SETUP_GUIDE =
            "Create an API key in the Anthropic Console at https://console.anthropic.com.";

    // This provider names its own credential rather than reading it from a central registry. A
    // second API provider then adds its own id instead of editing a shared table.
    static final SecretId API_KEY = new SecretId(PROVIDER_ID, "ANTHROPIC_API_KEY");

    // The response ceiling, which doubles as a per-call cost cap. A verdict runs about 70 tokens,
    // so the longest list a sheet can produce is ~3.5k for a dense 7x7 grid. The rest is a
    // reasoning allowance, since a model that reasons before answering spends from this same
    // ceiling. Reasoning that squeezes out the verdict list arrives as truncated JSON.
    private static final long MAX_TOKENS = 16384;
    private static final int DEFAULT_TRANSPORT_RETRIES = 2;
    // A check always has someone waiting on its answer, so it fails rather than retries. The SDK's
    // backoff would otherwise spend a minute on a service that is down, with the surface that
    // asked reading as hung.
    private static final int CHECK_RETRIES = 0;
    // The length of a wait somebody is watching, rather than the length the service might take.
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);
    // A bound on a list that runs to tens of entries. The paging is driven by what the service
    // says rather than by anything here, so it gets a ceiling.
    private static final long CHECK_MODEL_CEILING = 500;
    // What a fresh install opens on, before a key and a network can answer what the account really
    // runs. Least capable first, so a surface with no recommendation to fall back on lands on the
    // cheapest rather than the dearest. Labels stay plain display names, so this list and the
    // account's own read alike when one replaces the other.
    private static final ModelCatalog MODELS = new ModelCatalog(List.of(
            new ModelOption("claude-haiku-4-5", "Claude Haiku 4.5"),
            new ModelOption("claude-sonnet-5", "Claude Sonnet 5"),
            new ModelOption("claude-opus-5", "Claude Opus 5")),
            "claude-sonnet-5");
    // What the service appends when it names a model by a dated snapshot rather than by the plain
    // id above. Anchored on the date shape because a suffix rule that took anything would read a
    // later point release as a snapshot of the version it succeeds.
    private static final Pattern SNAPSHOT_SUFFIX = Pattern.compile("-\\d{8}");

    // A field the validator reads and refuses when blank. Carried on every branch, including the
    // ones where the field is optional, where it costs nothing: an action that ignores the field
    // ignores it whatever it holds.
    private static final Map<String, Object> NON_BLANK_STRING_SCHEMA = Map.of("type", "string", "minLength", 1);

    // Anchored, because a JSON Schema pattern searches where ShardValidator's own matcher demands
    // the whole value. Reusing that validator's expression verbatim would accept "FOO-bar", which it
    // then refuses.
    //
    // No maxLength beside it, though the validator caps a slug at 24. A ceiling truncates rather
    // than refuses, and two truncated-alike groups share a Duplicates folder whenever their keepers
    // fall in the same year-month. The validator catches that only when both groups brought a chosen
    // keeper, since the merged id then carries two and WrongChosenCount fires. A merge where one
    // side brought only rejects passes validation and lands silently.
    private static final Map<String, Object> GROUP_SLUG_SCHEMA =
            Map.of("type", "string", "minLength", 1, "pattern", "^[a-z0-9]+(-[a-z0-9]+)*$");

    private final CullerPrompt prompt;
    private final ShardCodec shardCodec;
    private final SidecarReader sidecarReader;
    private final CullSettings settings;
    // Built per call rather than cached. A cached client would have to notice a credential the user
    // changed in Settings, and no signal for that reaches this class. Fresh construction has no
    // stale state to get wrong, and each caller closes what it opened.
    private final Supplier<AnthropicClient> clientFactory;
    private final Function<CullProviderSettings, AnthropicClient> checkClientFactory;
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
     * @param secretStore {@link SecretStore} where this provider's API key is stored
     */
    @Autowired
    AnthropicCuller(final CullerPrompt prompt, final ShardCodec shardCodec, final SidecarReader sidecarReader,
                    final CullSettings settings, final SecretStore secretStore) {
        this(prompt, shardCodec, sidecarReader, settings,
                () -> defaultClient(settings.providerSettings(PROVIDER_ID), secretStore),
                providerSettings -> checkClient(providerSettings, secretStore));
    }

    /**
     * Constructs the culler with injectable client factories, for tests.
     *
     * <p>Two factories rather than one, because a check and a cull want different clients. Culling
     * rides the configured transport retries; a check refuses them.
     *
     * <p>The check factory takes the provider settings to check, rather than closing over the
     * stored ones, so a candidate endpoint that has not been saved reaches the same client-building
     * logic the stored one does.
     *
     * @param prompt {@link CullerPrompt} the prompt builder
     * @param shardCodec {@link ShardCodec} reads and writes per-montage shards
     * @param sidecarReader {@link SidecarReader} reads per-montage sidecars
     * @param settings {@link CullSettings} the cull settings
     * @param clientFactory a {@link Supplier} of {@link AnthropicClient}, builds the Anthropic client used to call
     * the model
     * @param checkClientFactory a {@link Function} from {@link CullProviderSettings} to {@link AnthropicClient},
     * builds the client used to check the given provider settings
     */
    AnthropicCuller(final CullerPrompt prompt, final ShardCodec shardCodec, final SidecarReader sidecarReader,
                    final CullSettings settings, final Supplier<AnthropicClient> clientFactory,
                    final Function<CullProviderSettings, AnthropicClient> checkClientFactory) {
        this.prompt = prompt;
        this.shardCodec = shardCodec;
        this.sidecarReader = sidecarReader;
        this.settings = settings;
        this.clientFactory = clientFactory;
        this.checkClientFactory = checkClientFactory;
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
     * {@inheritDoc}
     *
     * <p>A model id is the one value this provider cannot run without. Declaring it required is
     * what catches a missing one while a user is still looking at the field, rather than at cull
     * time against settings they last saw accepted.
     */
    @Override
    public VisionProviderDescriptor describe() {
        return new VisionProviderDescriptor(PROVIDER_ID,
                "Anthropic (calls a vision model from inside Sluice)",
                Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT, ProviderSetting.CREDENTIAL),
                Set.of(ProviderSetting.MODEL),
                API_KEY, MODELS, DEFAULT_ENDPOINT, SETUP_GUIDE);
    }

    /**
     * Says this provider calls a model itself, so a caller reads a refusal as a genuine failure.
     *
     * @return {@link ProviderType} always {@link ProviderType#API}
     */
    @Override
    public ProviderType type() {
        return ProviderType.API;
    }

    /**
     * Asks the service to list its models, which authenticates the stored key and answers what that
     * key can run in the same round trip. Listing generates no tokens, so asking costs nothing.
     *
     * <p>Every outcome is a value. Two exception families separate a key the service does not know
     * from an account not entitled to this. Everything else lands on unreachable, carrying whatever
     * it said.
     *
     * <p>The catch-all is what makes that true rather than intended. An endpoint is a field a user
     * types into, and the HTTP client rejects an unparseable one from no family this class could
     * enumerate. A credential store can refuse to answer the same way.
     *
     * @return {@link ProviderCheck} what the service said
     */
    @Override
    public ProviderCheck check() {
        return this.checkAgainst(this.settings.providerSettings(PROVIDER_ID));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checked against the given settings rather than what is stored. A URL typed into the
     * endpoint field and not yet saved can be tried before it is committed.
     *
     * @param candidate {@link CullProviderSettings} the connection settings to check
     * @return {@link ProviderCheck} what the service said
     */
    @Override
    public ProviderCheck check(final CullProviderSettings candidate) {
        return this.checkAgainst(candidate);
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
     * @param progress {@link ProgressCallback} progress callback ticked once for each montage the
     *        run finished with, judged or skipped. One the ceiling refused, or one a stop
     *        interrupted, is not ticked
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
     * @param progress {@link ProgressCallback} progress callback ticked once for each montage the
     *        run finished with, judged or skipped. One the ceiling refused, or one a stop
     *        interrupted, is not ticked
     * @param cancellation {@link CancellationSignal} signal checked between montages to stop early
     * @return {@link CullReport} the cull report
     * @throws CullException if a montage's response fails validation and the corrective retry does too
     */
    @Override
    public CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress,
                           final CancellationSignal cancellation)
            throws CullException {
        final String model = this.requiredModel();
        final String systemPrompt = this.prompt.systemPrompt(prep.categories());
        final List<String> categoryNames = prep.categoryNames();
        final JsonOutputFormat.Schema schema = responseSchema(categoryNames);
        final SpendCeiling ceiling = opts.ceiling();
        long inputTokens = 0;
        long outputTokens = 0;
        int culled = 0;
        int skipped = 0;
        int apiCalls = 0;
        // What the token bound is measured against. A montage resumed from an existing shard cost
        // nothing, so counting it would hand the run an allowance it never earned.
        int montagesAttempted = 0;
        boolean stoppedAtCeiling = false;
        final int total = prep.entries().size();
        int ordinal = 0;
        // Every readable sidecar is read up front, so validation sees the whole in-scope set the
        // shard contract defines. Accepted shards still accumulate one montage at a time, and
        // earlier ones are known clean, so any fresh problem implicates the current montage.
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
            while (ordinal < total && !cancellation.isCancelled() && !stoppedAtCeiling) {
                final String montage = prep.entries().get(ordinal);
                ordinal++;
                final List<SidecarPhotoEntry> entries = entriesByMontage.get(montage);
                final Path shardPath = prep.prepDir().resolve(MontageNaming.shardFileFor(montage));
                boolean counted = false;
                if (entries == null || this.resumesExistingShard(shardPath, montage, entries, acceptedShards,
                        scopeSrcs, categoryNames)) {
                    skipped++;
                    counted = true;
                } else if (ceilingExhausted(ceiling, apiCalls, montagesAttempted, inputTokens + outputTokens)) {
                    // Checked before this montage's first call rather than only after the last one,
                    // so the run stops owing nothing further. This montage and every one after it
                    // keep their place: they have no shard, so a resume dispatches for them.
                    stoppedAtCeiling = true;
                } else {
                    montagesAttempted++;
                    final MessageCreateParams request = request(model, systemPrompt,
                            this.prompt.userTurn(prep.scope(), montage, ordinal, total, entries),
                            montageImageBase64(prep.prepDir(), montage), schema);
                    final Message response = client.messages().create(request);
                    apiCalls++;
                    inputTokens += response.usage().inputTokens();
                    outputTokens += response.usage().outputTokens();
                    AttemptOutcome outcome = this.attempt(montage, entries, response, acceptedShards,
                            scopeSrcs, categoryNames);
                    if (outcome.shard() == null && !cancellation.isCancelled()) {
                        if (callsExhausted(ceiling, apiCalls)) {
                            // The correction this montage needs would be the call past the bound.
                            // Refusing it is what the bound is for, so the run ends here rather than
                            // leaving one montage quietly unjudged.
                            stoppedAtCeiling = true;
                        } else {
                            final Message retryResponse = client.messages().create(retryRequest(request,
                                    responseText(response), this.prompt.correctionTurn(outcome.problems())));
                            apiCalls++;
                            inputTokens += retryResponse.usage().inputTokens();
                            outputTokens += retryResponse.usage().outputTokens();
                            final AttemptOutcome retried = this.attempt(montage, entries, retryResponse,
                                    acceptedShards, scopeSrcs, categoryNames);
                            if (retried.shard() == null) {
                                // Thrown only when uncancelled, so a cancellation requested while
                                // the retry call was in flight never surfaces as a retry-failure
                                // CullException.
                                if (!cancellation.isCancelled()) {
                                    throw retryFailedException(prep.scope(), montage, outcome.problems(),
                                            retried.problems(),
                                            new CullReport(culled, skipped, apiCalls,
                                                    new TokenSpend(inputTokens, outputTokens, PROVIDER_ID, model),
                                                    false));
                                }
                            } else {
                                outcome = retried;
                            }
                        }
                    }
                    if (outcome.shard() != null) {
                        this.shardCodec.write(shardPath, outcome.shard());
                        culled++;
                        counted = true;
                    }
                }
                if (counted) {
                    progress.tick(ordinal, total);
                }
            }
        } finally {
            client.close();
        }
        if (stoppedAtCeiling) {
            log.info("The sift of {} stopped at its spend ceiling after {} call(s) and {} token(s)",
                    prep.scope(), apiCalls, inputTokens + outputTokens);
        }
        return new CullReport(culled, skipped, apiCalls,
                new TokenSpend(inputTokens, outputTokens, PROVIDER_ID, model), stoppedAtCeiling);
    }

    /**
     * Counts what one call over prep's first montage would carry, without sending it.
     *
     * <p>The count route generates nothing, and Anthropic does not bill it. What it is given is
     * built by the same builder the paid call uses, so the schema and system prompt are counted too
     * rather than being left out of the figure.
     *
     * <p>One montage priced rather than all of them, since only the photo table varies between
     * them. A run's last sheet can be shorter than a full one, which makes this an over-estimate
     * for a scope the grid does not divide evenly, and the ceiling built on it correspondingly
     * looser.
     *
     * <p>Every way this can fail answers {@link SpendForecast.Unknown}. An estimate that cannot be
     * built must not be what stops a run from starting.
     *
     * @param prep {@link PrepDir} the prep directory a run would be made over
     * @return {@link SpendForecast} what one call would carry, or why that is not known
     */
    @Override
    public SpendForecast forecast(final PrepDir prep) {
        if (prep.entries().isEmpty()) {
            return new SpendForecast.Counted(0);
        }
        final String montage = prep.entries().getFirst();
        try {
            final List<SidecarPhotoEntry> entries =
                    this.readEntries(prep.prepDir().resolve(montage + ".json"))
                            .orElseThrow(() -> new IllegalStateException("no readable sidecar for " + montage));
            final MessageCreateParams request = request(this.requiredModel(),
                    this.prompt.systemPrompt(prep.categories()),
                    this.prompt.userTurn(prep.scope(), montage, 1, prep.entries().size(), entries),
                    montageImageBase64(prep.prepDir(), montage),
                    responseSchema(prep.categoryNames()));
            final AnthropicClient client = this.clientFactory.get();
            try {
                return new SpendForecast.Counted(client.messages().countTokens(tokenCountRequest(request)).inputTokens());
            } finally {
                client.close();
            }
        } catch (final RuntimeException e) {
            log.info("Could not count what a sift of {} would send", prep.scope(), e);
            return new SpendForecast.Unknown(messageOf(e));
        }
    }

    /**
     * Package-private so the live verify can wrap the client this builds instead of its own.
     *
     * @param providerSettings {@link CullProviderSettings} the configured Anthropic provider settings
     * @param secretStore {@link SecretStore} where this provider's API key is stored
     * @return {@link AnthropicClient} the built Anthropic client
     * @throws MissingCredentialException if no tier holds this provider's API key
     */
    static AnthropicClient defaultClient(final CullProviderSettings providerSettings,
            final SecretStore secretStore) {
        // Transport failures (429/5xx/timeouts) are the SDK's own retry family: exponential
        // backoff honoring retry-after, separate from the one content retry above.
        final Integer maxRetries = providerSettings.maxRetries();
        return client(providerSettings, secretStore,
                maxRetries == null ? DEFAULT_TRANSPORT_RETRIES : maxRetries, null);
    }

    /**
     * The client a credential check runs on. Same key and endpoint as a cull. No retries, and a
     * timeout short enough that a person keeps waiting.
     *
     * @param providerSettings {@link CullProviderSettings} the configured Anthropic provider settings
     * @param secretStore {@link SecretStore} where this provider's API key is stored
     * @return {@link AnthropicClient} the built Anthropic client
     * @throws MissingCredentialException if no tier holds this provider's API key
     */
    private static AnthropicClient checkClient(final CullProviderSettings providerSettings,
            final SecretStore secretStore) {
        return client(providerSettings, secretStore, CHECK_RETRIES, CHECK_TIMEOUT);
    }

    /**
     * Builds a client on this provider's stored key and configured endpoint. The refusal for a
     * missing key is the one every route out of this class raises, so a user meets the same
     * instructions whichever one they took.
     *
     * @param providerSettings {@link CullProviderSettings} the configured Anthropic provider settings
     * @param secretStore {@link SecretStore} where this provider's API key is stored
     * @param maxRetries int how many times a failed transport call is tried again
     * @param timeout the ceiling on one call, or null to leave the SDK's own
     * @return {@link AnthropicClient} the built Anthropic client
     * @throws MissingCredentialException if no tier holds this provider's API key
     */
    private static AnthropicClient client(final CullProviderSettings providerSettings,
            final SecretStore secretStore, final int maxRetries, final @Nullable Duration timeout) {
        final String apiKey = secretStore.secret(API_KEY).orElseThrow(() -> new MissingCredentialException(API_KEY,
                "No API key is stored for the '" + PROVIDER_ID + "' vision provider; add one in Settings, "
                        + "or set the " + API_KEY.environmentVariable() + " environment variable"));
        final var builder = AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(maxRetries);
        if (timeout != null) {
            builder.timeout(timeout);
        }
        final String endpoint = providerSettings.endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.baseUrl(endpoint);
        }
        return builder.build();
    }

    /**
     * Whether either arm of the ceiling has been reached, asked before a montage's first call.
     *
     * @param ceiling {@link SpendCeiling} the run's ceiling, or null when the run is unbounded
     * @param apiCalls how many calls the run has made
     * @param montagesAttempted how many montages the run has dispatched for
     * @param tokensConsumed how many tokens the run has consumed
     * @return boolean true when the run may not start another montage
     */
    private static boolean ceilingExhausted(final @Nullable SpendCeiling ceiling, final int apiCalls,
                                            final int montagesAttempted, final long tokensConsumed) {
        return callsExhausted(ceiling, apiCalls)
                || (ceiling != null && ceiling.tokensExhausted(montagesAttempted, tokensConsumed));
    }

    /**
     * Whether the run has made every call it is allowed.
     *
     * <p>What this catches is a defect that has stopped following the montage list. A bound
     * allowing two calls per montage cannot be reached by a loop that walks that same list taking
     * at most two each.
     *
     * @param ceiling {@link SpendCeiling} the run's ceiling, or null when the run is unbounded
     * @param apiCalls how many calls the run has made
     * @return boolean true when the run may not make another call
     */
    private static boolean callsExhausted(final @Nullable SpendCeiling ceiling, final int apiCalls) {
        return ceiling != null && apiCalls >= ceiling.maxCalls();
    }

    /**
     * Restates a request as a count of the same body.
     *
     * @param sending {@link MessageCreateParams} the request that would be sent
     * @return {@link MessageCountTokensParams} the same body, addressed to the counting route
     */
    private static MessageCountTokensParams tokenCountRequest(final MessageCreateParams sending) {
        final MessageCountTokensParams.Builder counting = MessageCountTokensParams.builder()
                .model(sending.model())
                .messages(sending.messages());
        sending.system()
                .map(system -> MessageCountTokensParams.System.ofString(system.asString()))
                .ifPresent(counting::system);
        sending.outputConfig().ifPresent(counting::outputConfig);
        return counting.build();
    }

    /**
     * The shared body of {@link #check()} and {@link #check(CullProviderSettings)}, differing only
     * in which provider settings the client is built against.
     *
     * @param providerSettings {@link CullProviderSettings} the connection settings to check
     * @return {@link ProviderCheck} what the service said
     */
    private ProviderCheck checkAgainst(final CullProviderSettings providerSettings) {
        final AnthropicClient client;
        try {
            client = this.checkClientFactory.apply(providerSettings);
        } catch (final MissingCredentialException e) {
            return new ProviderCheck.NoCredential();
        } catch (final RuntimeException e) {
            return new ProviderCheck.Unreachable(messageOf(e));
        }
        try {
            final List<ModelOption> offerableModels = client.models().list().autoPager().stream()
                    .limit(CHECK_MODEL_CEILING)
                    .filter(AnthropicCuller::offerable)
                    .map(model -> new ModelOption(model.id(), model.displayName()))
                    .toList();
            if (offerableModels.isEmpty()) {
                return new ProviderCheck.NoUsableModels();
            }
            final List<ModelOption> rankedModels = rankModels(offerableModels);
            return new ProviderCheck.Accepted(new ModelCatalog(rankedModels, recommendedAmong(rankedModels)));
        } catch (final UnauthorizedException e) {
            return new ProviderCheck.Rejected();
        } catch (final PermissionDeniedException e) {
            return new ProviderCheck.Refused(messageOf(e));
        } catch (final RuntimeException e) {
            return new ProviderCheck.Unreachable(messageOf(e));
        } finally {
            client.close();
        }
    }

    /**
     * Whether one model out of the service's list can be offered as a choice here. It has to read
     * an image and answer against a JSON schema, which is what a montage and a verdict are. A model
     * reporting nothing about itself is not offered, since nothing says it can do either.
     *
     * @param model {@link ModelInfo} one model the service listed
     * @return boolean true if this model can be offered
     */
    private static boolean offerable(final ModelInfo model) {
        try {
            return !model.id().isBlank() && !model.displayName().isBlank()
                    && model.capabilities()
                    .filter(capabilities -> capabilities.imageInput().supported()
                            && capabilities.structuredOutputs().supported())
                    .isPresent();
        } catch (final AnthropicInvalidDataException e) {
            // The service described this model in a shape this app cannot read. Dropping the one
            // entry leaves the rest of the account's list offerable.
            return false;
        }
    }

    /**
     * Orders the account's own models the way {@link #MODELS} orders the ones this class knows.
     * Anything it does not know follows, in the order the service gave. A model the service names
     * by a dated snapshot takes the rank of the plain id that snapshot is of.
     *
     * <p>Sorting the known models ahead of the rest keeps an unknown one out of the first position,
     * which is where a surface with no recommendation to fall back on starts.
     *
     * @param offerableModels a {@link List} of {@link ModelOption}, the models this account can be offered
     * @return a {@link List} of {@link ModelOption} the same models, in the order to offer them
     */
    private static List<ModelOption> rankModels(final List<ModelOption> offerableModels) {
        final List<String> rankedIds = MODELS.options().stream().map(ModelOption::id).toList();
        return offerableModels.stream()
                .sorted(Comparator.comparingInt(option -> rankOf(rankedIds, option.id())))
                .toList();
    }

    /**
     * Where an offered model sits in rankedIds, or one past its end when it sits on none.
     *
     * @param rankedIds a {@link List} of {@link String} model ids, in the order this class ranks them
     * @param offeredId {@link String} id of a model the account can be offered
     * @return int the rank to sort this model by
     */
    private static int rankOf(final List<String> rankedIds, final String offeredId) {
        return IntStream.range(0, rankedIds.size())
                .filter(rank -> isSameModel(rankedIds.get(rank), offeredId))
                .findFirst()
                .orElse(rankedIds.size());
    }

    /**
     * Whether an id this class carries and one the service offered name the same model, allowing
     * for the service naming it by a dated snapshot.
     *
     * @param rankedId {@link String} a model id from {@link #MODELS}
     * @param offeredId {@link String} id of a model the account can be offered
     * @return boolean true when both name the same model
     */
    private static boolean isSameModel(final String rankedId, final String offeredId) {
        return offeredId.equals(rankedId)
                || (offeredId.startsWith(rankedId)
                && SNAPSHOT_SUFFIX.matcher(offeredId.substring(rankedId.length())).matches());
    }

    /**
     * This provider's own recommendation, but only when the account can actually run it. A
     * recommendation nobody can select would default a picker to a model the service refuses.
     *
     * <p>The answer is the id the account was offered, which is the dated one where the service
     * named a snapshot. This class's own id would name a model absent from the list it is offered
     * beside.
     *
     * @param offerableModels a {@link List} of {@link ModelOption}, the models this account can be offered
     * @return {@link String} the offered id of the recommended model, or null when it is not among
     *     them
     */
    private static @Nullable String recommendedAmong(final List<ModelOption> offerableModels) {
        final String recommended = MODELS.recommended();
        if (recommended == null) {
            return null;
        }
        return offerableModels.stream()
                .map(ModelOption::id)
                .filter(offeredId -> isSameModel(recommended, offeredId))
                .findFirst()
                .orElse(null);
    }

    /**
     * What a failure said, for an outcome that carries the service's own words.
     *
     * @param failure {@link RuntimeException} what was raised
     * @return {@link String} the failure's message, or its type when it carried none
     */
    private static String messageOf(final RuntimeException failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /**
     * Skips a montage whose existing shard still passes the full contract against everything
     * accepted so far - the resume path for an interrupted run. An unreadable or contract-breaking
     * shard is not an error here. It reports false, and the caller re-culls the montage, the fresh
     * shard overwriting the bad one.
     *
     * @param shardPath {@link Path} path of the montage's shard file
     * @param montage {@link String} the montage name
     * @param entries a {@link List} of {@link SidecarPhotoEntry}, the montage's sidecar photo entries
     * @param acceptedShards a {@link List} of {@link ShardFile}, shards accepted so far, mutated on acceptance
     * @param scopeSrcs a {@link List} of {@link Path}, every in-scope source path for the run
     * @param categoryNames a {@link List} of {@link String}, the cull category names this run recorded
     * @return boolean true if the existing shard is valid and was accepted
     */
    private boolean resumesExistingShard(final Path shardPath, final String montage,
                                         final List<SidecarPhotoEntry> entries,
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
        return this.acceptIfValid(montage, existing, acceptedShards, srcsOf(entries), scopeSrcs,
                categoryNames).isEmpty();
    }

    /**
     * The source paths one montage's sidecar entries name, in the order the sheet laid them out.
     *
     * @param entries a {@link List} of {@link SidecarPhotoEntry}, one montage's sidecar photo entries
     * @return a {@link List} of {@link Path}, their source paths
     */
    private static List<Path> srcsOf(final List<SidecarPhotoEntry> entries) {
        return entries.stream().map(SidecarPhotoEntry::src).toList();
    }

    /**
     * One response's full journey: response text -> candidate shard -> accumulated validation.
     *
     * @param montage {@link String} the montage name
     * @param entries a {@link List} of {@link SidecarPhotoEntry}, the montage's sidecar photo entries
     * @param response {@link Message} the model's response to validate
     * @param acceptedShards a {@link List} of {@link ShardFile}, shards accepted so far
     * @param scopeSrcs a {@link List} of {@link Path}, every in-scope source path for the run
     * @param categoryNames a {@link List} of {@link String}, the cull category names this run recorded
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
                this.acceptIfValid(montage, shard, acceptedShards, srcsOf(entries), scopeSrcs, categoryNames);
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
     * @param sheetSrcs a {@link List} of {@link Path}, the source paths this one montage showed
     * @param scopeSrcs a {@link List} of {@link Path}, every in-scope source path for the run
     * @param categoryNames a {@link List} of {@link String}, the cull category names this run recorded
     * @return a {@link List} of {@link String}, validation problems found, empty if the shard was accepted
     */
    private List<String> acceptIfValid(final String montage, final DecisionShard shard,
                                       final List<ShardFile> acceptedShards, final List<Path> sheetSrcs,
                                       final List<Path> scopeSrcs, final List<String> categoryNames) {
        acceptedShards.add(new ShardFile(montage, shard, sheetSrcs));
        final ValidationReport report = this.validator.validate(acceptedShards, scopeSrcs, categoryNames, List.of());
        if (!report.valid()) {
            acceptedShards.removeLast();
        }
        return report.findings().stream().map(Finding::describe).toList();
    }

    /**
     * Turns one montage's response into a candidate shard, or reports every response-level problem
     * found and returns null. Only what exists solely in the response is checked here: tile-index
     * coverage and the name match. Everything shard-shaped stays with {@link ShardValidator}, the
     * contract's single source of truth.
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
        final var collected = new ArrayList<Verdict>();
        final var seenIndices = new HashSet<Integer>();
        final List<@Nullable RawVerdict> verdicts = parsed.verdicts() == null ? List.of() : parsed.verdicts();
        for (final RawVerdict verdict : verdicts) {
            collectVerdict(verdict, entries, seenIndices, collected, problems);
        }
        if (!problems.isEmpty()) {
            return null;
        }
        return new DecisionShard(montage, collected);
    }

    /**
     * Checks one verdict's response-level contract: index in range and unseen, name matching the
     * sidecar entry at that index. When the contract holds, the verdict it maps to is collected,
     * a keep included.
     *
     * @param verdict {@link RawVerdict} the raw verdict to check
     * @param entries a {@link List} of {@link SidecarPhotoEntry}, the montage's sidecar photo entries
     * @param seenIndices a {@link HashSet} of {@link Integer}, indices already claimed by a verdict, mutated by this
     * call
     * @param collected a {@link List} of {@link Verdict}, accumulator for collected verdicts, mutated by this call
     * @param problems a {@link List} of {@link String}, accumulator for problems found, mutated by this call
     */
    private static void collectVerdict(final @Nullable RawVerdict verdict, final List<SidecarPhotoEntry> entries,
                                       final HashSet<Integer> seenIndices, final List<Verdict> collected,
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
        final String action = ShardCodec.orEmpty(verdict.action());
        collected.add(switch (action) {
            case VerdictAction.KEEP -> new Keep(entry.src());
            case VerdictAction.NEAR_DUP_CHOSEN -> new NearDupChosen(entry.src(), ShardCodec.orEmpty(verdict.group()),
                    ShardCodec.orEmpty(verdict.chosenReason()));
            case VerdictAction.NEAR_DUP_REJECT -> new NearDupReject(entry.src(), ShardCodec.orEmpty(verdict.group()),
                    ShardCodec.orEmpty(verdict.reason()));
            default -> new Classification(entry.src(), action, ShardCodec.orEmpty(verdict.reason()));
        });
    }

    /**
     * Reads one montage's sidecar, translating an unreadable one into the empty case rather than
     * failing the run. A sidecar names the photos its montage's tile grid shows. Without one there
     * is nothing to key the model's verdicts back to files, so that montage cannot be culled.
     *
     * <p>Skipping it beats failing the run, because the apply phase reports a corrupt sidecar and
     * offers the choice of trusting any existing shard or setting the montage aside. Failing here
     * would put the run out of reach of the answer to that question. Damaged content and a read
     * that merely failed are both tolerated, neither being a judgement this class is placed to
     * make.
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
     * Parses the response text into a raw verdict list, recording a problem if it can't be parsed.
     *
     * <p>A response the ceiling cut short is reported as that, before the parse. Its text is a
     * verdict list that stops mid-token, so parsing it would blame the model's JSON for a budget
     * that ran out. A model that cannot follow a schema and a sheet whose verdicts and reasoning
     * together do not fit call for different answers.
     *
     * @param response {@link Message} the model's response to parse
     * @param problems a {@link List} of {@link String}, accumulator for problems found, mutated by this call
     * @return {@link RawResponse} the parsed response, or null if it couldn't be parsed
     */
    private @Nullable RawResponse parse(final Message response, final List<String> problems) {
        if (response.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            problems.add("response was cut off at the " + MAX_TOKENS
                    + "-token ceiling, before the verdict list was complete");
            return null;
        }
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
     * Builds the exception thrown when a montage's corrective retry still fails validation.
     *
     * @param scope {@link String} the cull scope
     * @param montage {@link String} the montage that failed
     * @param firstProblems a {@link List} of {@link String}, problems from the first attempt
     * @param retryProblems a {@link List} of {@link String}, problems from the retry attempt
     * @param report {@link CullReport} what the run had judged and consumed before giving up on this montage
     * @return {@link CullException} the exception naming both attempts' problems
     */
    private static CullException retryFailedException(final String scope, final String montage,
                                                      final List<String> firstProblems,
                                                      final List<String> retryProblems,
                                                      final CullReport report) {
        return new CullException("The sifting for " + scope + " failed at sheet " + montage
                + " and a corrective retry did not fix it."
                + "\nFirst attempt (" + firstProblems.size() + " problem(s)):\n - "
                + String.join("\n - ", firstProblems)
                + "\nRetry (" + retryProblems.size() + " problem(s)):\n - "
                + String.join("\n - ", retryProblems), report);
    }

    /**
     * Assembles one montage's complete API request. The image block precedes the text turn per
     * Anthropic's vision guidance: models resolve references into an image better when the image
     * comes first. The schema rides along as a structured-output format, which is a request rather
     * than a guarantee, so {@link #parse} records a problem instead of throwing when the response
     * is not one. No sampling parameters - current Anthropic models reject them outright.
     *
     * <p>No thinking parameter and no effort parameter either, so each model reasons at whatever
     * depth it reasons by default. Omitting both is the only shape every current model accepts, and
     * whether depth helps a model read a contact sheet is unmeasured.
     *
     * @param model {@link String} the model id to request
     * @param systemPrompt {@link String} the shared system prompt
     * @param userTurn {@link String} the montage's user turn text
     * @param imageBase64 {@link String} the montage image, base64-encoded
     * @param schema {@link JsonOutputFormat.Schema} this run's structured-output contract
     * @return {@link MessageCreateParams} the assembled request
     */
    private static MessageCreateParams request(final String model, final String systemPrompt,
                                               final String userTurn, final String imageBase64,
                                               final JsonOutputFormat.Schema schema) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
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
                                .schema(schema)
                                .build())
                        .build())
                .build();
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
     * The structured-output contract one run's requests carry: a verdict list whose entries are a
     * discriminated union on action.
     *
     * <p>Built per run rather than held as a constant. The classification branch enumerates the
     * run's own recorded category names, the same set {@link ShardValidator} judges the responses
     * against. A static one would go out of step with the validator the first time someone edited
     * a card.
     *
     * <p>Naming the required fields per action puts them in the contract the model is sent, instead
     * of leaving the model to discover them by being refused. Each gap discovered that way costs a
     * paid corrective retry. {@link ShardValidator} stays the single authority either way. Its
     * cross-verdict and cross-shard rules have no expression in a schema describing one verdict.
     *
     * <p>A string constraint here shapes the answer rather than refusing it, so its direction
     * decides whether it is safe. A floor only steers an empty value away, which is what
     * {@code minLength} does for a reason and the slug's character rule does for a shape. A ceiling
     * truncates instead, so the slug's 24-character cap stays with the validator - see
     * {@link #GROUP_SLUG_SCHEMA} for what a silent truncation costs.
     *
     * @param categories a {@link List} of {@link String}, the run's recorded category names
     * @return {@link JsonOutputFormat.Schema} the schema to send with every montage in this run
     * @throws IllegalStateException if the run recorded no categories
     */
    private static JsonOutputFormat.Schema responseSchema(final List<String> categories) {
        // An empty list would build a branch matching no string at all, and a request nobody has
        // put through the service. Refusing here rather than trusting the caller's ordering, since
        // what stands between the two is which of these lines runs first.
        if (categories.isEmpty()) {
            throw new IllegalStateException("This run recorded no photo categories, and there is "
                    + "nothing to ask a model to sort photos into without them");
        }
        return schemaOf(Map.of(
                "type", "object",
                "properties", Map.of(
                        "verdicts", Map.of(
                                "type", "array",
                                "items", Map.of("anyOf", List.of(
                                        verdictBranch(Map.of("type", "string", "const", VerdictAction.KEEP),
                                                List.of()),
                                        verdictBranch(Map.of("type", "string", "const", VerdictAction.NEAR_DUP_CHOSEN),
                                                List.of("group", "chosen_reason")),
                                        verdictBranch(Map.of("type", "string", "const", VerdictAction.NEAR_DUP_REJECT),
                                                List.of("group", "reason")),
                                        verdictBranch(Map.of("type", "string", "enum", categories),
                                                List.of("reason")))))),
                "required", List.of("verdicts"),
                "additionalProperties", false));
    }

    /**
     * One action kind's branch of the verdict union.
     *
     * <p>Every branch declares every field a verdict may carry, and the branches differ only in
     * what they pin action to and what they demand. Narrowing a branch to the fields its own action
     * needs would refuse a keep that volunteered a reason, which nothing downstream objects to. The
     * point here is to stop paying for refusals, so a branch that invented one would work against
     * it.
     *
     * @param action a {@link Map} of {@link String} to {@link Object}, the sub-schema pinning this branch's action
     * @param alsoRequired a {@link List} of {@link String}, the fields this action needs beyond the common three
     * @return a {@link Map} of {@link String} to {@link Object}, the branch, as a plain nested map
     */
    private static Map<String, Object> verdictBranch(final Map<String, Object> action,
                                                     final List<String> alsoRequired) {
        final var required = new ArrayList<>(List.of("index", "name", "action"));
        required.addAll(alsoRequired);
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "index", Map.of("type", "integer"),
                        "name", Map.of("type", "string"),
                        "action", action,
                        "reason", NON_BLANK_STRING_SCHEMA,
                        "group", GROUP_SLUG_SCHEMA,
                        "chosen_reason", NON_BLANK_STRING_SCHEMA),
                "required", List.copyOf(required),
                "additionalProperties", false);
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
        final String model = this.settings.providerSettings(PROVIDER_ID).model();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("sluice.sift.provider-settings." + PROVIDER_ID
                    + ".model is not set; the '" + PROVIDER_ID
                    + "' vision provider needs the model id to request");
        }
        return model;
    }
}
