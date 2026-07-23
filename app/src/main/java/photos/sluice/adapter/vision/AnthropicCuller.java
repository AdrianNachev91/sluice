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
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ValidationReport;
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
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// The provider that calls the user's configured Anthropic vision model from inside the app. Each
// montage is one stateless request: the shared system prompt, the montage JPEG, and the photo
// table. A structured-output schema constrains the response to a JSON verdict list. The model must
// return a verdict for every tile; that forces a look at every photo instead of skimming past
// faint junk, and gives a hard completeness check. Keep verdicts are stripped before the shard is
// written, so the on-disk contract stays the external-agent one: non-keep decisions only.
//
// The model references photos by tile index and filename, never by path. The response is resolved
// index -> sidecar src here. A name that does not match the sidecar entry at that index fails
// validation rather than healing. The mismatch signals a mis-keyed tile, and guessing which field
// to trust could set aside the wrong photo.
//
// Failure channels, split by who can fix them. A response that fails validation is a content
// problem the model itself can often fix. It gets one corrective retry that echoes the failed
// reply back with the full problem list. A second failure throws checked CullException naming the
// montage and both attempts' problems. The one-retry cap is deliberate, so a model that cannot
// cull a montage stops burning tokens. Missing connection settings (model id, API key) are the
// user's configuration to fix and fail unchecked with the property or variable name. A prep
// directory whose sidecar or montage image can't be read is broken app output. That fails
// unchecked too, because the scope needs re-prepping.
//
// A montage whose valid shard is already on disk is skipped, so an interrupted run re-invoked on
// the same prep directory finishes only the remainder. An invalid existing shard is re-culled and
// overwritten.
//
// The API client is built lazily inside cull(), never at startup, so the app boots without an API
// key for users on other providers. The factory seam exists for tests to inject a mock client.
@Component
class AnthropicCuller implements VisionCuller {

    // The response ceiling, which doubles as a per-call cost cap. Sizing: a verdict runs about
    // 70 tokens, so the largest list a sheet can produce is ~3.5k for a dense 7x7 grid. 8192
    // holds that worst case more than twice over.
    private static final long MAX_TOKENS = 8192;
    // A thinking run adds a reasoning allowance equal to the whole answer ceiling. Reasoning
    // shares the response budget. The extra 8192 (~300 tokens of deliberation per photo on a
    // full sheet) keeps even a long chain from squeezing out the verdict JSON.
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

    @Autowired
    AnthropicCuller(CullerPrompt prompt, ShardCodec shardCodec, SidecarReader sidecarReader,
            CullSettings settings) {
        this(prompt, shardCodec, sidecarReader, settings,
                () -> defaultClient(settings.providerSettings()));
    }

    AnthropicCuller(CullerPrompt prompt, ShardCodec shardCodec, SidecarReader sidecarReader,
            CullSettings settings, Supplier<AnthropicClient> clientFactory) {
        this.prompt = prompt;
        this.shardCodec = shardCodec;
        this.sidecarReader = sidecarReader;
        this.settings = settings;
        this.clientFactory = clientFactory;
    }

    // The model's verdict for one tile, as the response schema shapes it. Which fields a verdict
    // needs depends on its action. Absent ones become empty strings, so ShardValidator reports
    // them aggregated instead of one parse crash per gap.
    private record RawVerdict(@Nullable Integer index, @Nullable String name, @Nullable String action,
            @Nullable String reason, @Nullable String group,
            @JsonProperty("chosen_reason") @Nullable String chosenReason) {
    }

    private record RawResponse(@Nullable List<@Nullable RawVerdict> verdicts) {
    }

    // One attempt's result: the accepted shard, or the problems that rejected it (never both).
    private record AttemptOutcome(@Nullable DecisionShard shard, List<String> problems) {
    }

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public CullReport cull(PrepDir prep, CullOptions opts) throws CullException {
        String model = requiredModel();
        boolean thinking = Boolean.TRUE.equals(settings.providerSettings().thinking());
        String systemPrompt = prompt.systemPrompt();
        List<String> categoryNames = settings.categories().stream().map(CullCategory::name).toList();
        long inputTokens = 0;
        long outputTokens = 0;
        int culled = 0;
        int resumed = 0;
        int total = prep.entries().size();
        int ordinal = 0;
        // Accepted shards and srcs accumulate so each montage is validated against everything
        // already accepted, not alone - ShardValidator's cross-shard rules (a near-dup group id
        // reused by two montages, say) can only fire on the whole set. Earlier shards are known
        // clean, so any fresh problem implicates the current montage.
        var acceptedShards = new ArrayList<ShardFile>();
        var acceptedSrcs = new ArrayList<Path>();
        AnthropicClient client = clientFactory.get();
        try {
            for (String montage : prep.entries()) {
                ordinal++;
                List<SidecarPhotoEntry> entries =
                        sidecarReader.readEntries(prep.prepDir().resolve(montage + ".json"));
                Path shardPath = prep.prepDir().resolve(shardNameFor(montage));
                if (resumesExistingShard(shardPath, montage, entries, acceptedShards, acceptedSrcs,
                        categoryNames)) {
                    resumed++;
                } else {
                    MessageCreateParams request = request(model, thinking, systemPrompt,
                            prompt.userTurn(prep.scope(), montage, ordinal, total, entries),
                            montageImageBase64(prep.prepDir(), montage));
                    Message response = client.messages().create(request);
                    inputTokens += response.usage().inputTokens();
                    outputTokens += response.usage().outputTokens();
                    AttemptOutcome outcome = attempt(montage, entries, response, acceptedShards,
                            acceptedSrcs, categoryNames);
                    if (outcome.shard() == null) {
                        Message retryResponse = client.messages().create(retryRequest(request,
                                responseText(response), prompt.correctionTurn(outcome.problems())));
                        inputTokens += retryResponse.usage().inputTokens();
                        outputTokens += retryResponse.usage().outputTokens();
                        AttemptOutcome retried = attempt(montage, entries, retryResponse,
                                acceptedShards, acceptedSrcs, categoryNames);
                        if (retried.shard() == null) {
                            throw retryFailedException(prep.scope(), montage, outcome.problems(),
                                    retried.problems());
                        }
                        outcome = retried;
                    }
                    shardCodec.write(shardPath, outcome.shard());
                    culled++;
                }
            }
        } finally {
            client.close();
        }
        return new CullReport(culled, resumed, inputTokens, outputTokens);
    }

    // Skips a montage whose existing shard still passes the full contract against everything
    // accepted so far - the resume path for an interrupted run. An unreadable or contract-breaking
    // shard is not an error here. It reports false, and the caller re-culls the montage, the fresh
    // shard overwriting the bad one.
    private boolean resumesExistingShard(Path shardPath, String montage, List<SidecarPhotoEntry> entries,
            List<ShardFile> acceptedShards, List<Path> acceptedSrcs, List<String> categoryNames) {
        if (!Files.exists(shardPath)) {
            return false;
        }
        final DecisionShard existing;
        try {
            existing = shardCodec.read(shardPath);
        } catch (UncheckedIOException e) {
            return false;
        }
        return acceptIfValid(montage, existing, entries, acceptedShards, acceptedSrcs, categoryNames)
                .isEmpty();
    }

    // One response's full journey: response text -> candidate shard -> accumulated validation.
    private AttemptOutcome attempt(String montage, List<SidecarPhotoEntry> entries, Message response,
            List<ShardFile> acceptedShards, List<Path> acceptedSrcs, List<String> categoryNames) {
        var problems = new ArrayList<String>();
        DecisionShard shard = shardOf(montage, entries, response, problems);
        if (shard == null) {
            return new AttemptOutcome(null, problems);
        }
        List<String> validationProblems =
                acceptIfValid(montage, shard, entries, acceptedShards, acceptedSrcs, categoryNames);
        if (!validationProblems.isEmpty()) {
            return new AttemptOutcome(null, validationProblems);
        }
        return new AttemptOutcome(shard, List.of());
    }

    // Tentatively adds the shard to the accepted set and validates the whole set. A clean result
    // keeps it and returns no problems. Anything else rolls the addition back, so a retry or
    // re-cull starts from the same accepted state.
    private List<String> acceptIfValid(String montage, DecisionShard shard, List<SidecarPhotoEntry> entries,
            List<ShardFile> acceptedShards, List<Path> acceptedSrcs, List<String> categoryNames) {
        acceptedShards.add(new ShardFile(montage, shard));
        entries.stream().map(SidecarPhotoEntry::src).forEach(acceptedSrcs::add);
        ValidationReport report = validator.validate(acceptedShards, acceptedSrcs, categoryNames);
        if (!report.problems().isEmpty()) {
            acceptedShards.removeLast();
            acceptedSrcs.subList(acceptedSrcs.size() - entries.size(), acceptedSrcs.size()).clear();
        }
        return report.problems();
    }

    // Turns one montage's response into a candidate shard, or reports every response-level problem
    // found and returns null. Only what exists solely in the response is checked here: tile-index
    // coverage and the name match. Everything shard-shaped stays with ShardValidator, the
    // contract's single source of truth, which the caller runs over the accumulated set.
    private @Nullable DecisionShard shardOf(String montage, List<SidecarPhotoEntry> entries,
            Message response, List<String> problems) {
        RawResponse parsed = parse(response, problems);
        if (parsed == null) {
            return null;
        }
        var decisions = new ArrayList<Decision>();
        var seenIndices = new HashSet<Integer>();
        List<@Nullable RawVerdict> verdicts = parsed.verdicts() == null ? List.of() : parsed.verdicts();
        for (RawVerdict verdict : verdicts) {
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

    // Checks one verdict's response-level contract: index in range and unseen, name matching the
    // sidecar entry at that index. When the contract holds, the non-keep decision it maps to is
    // collected.
    private static void collectDecision(@Nullable RawVerdict verdict, List<SidecarPhotoEntry> entries,
            HashSet<Integer> seenIndices, List<Decision> decisions, List<String> problems) {
        if (verdict == null) {
            problems.add("null verdict entry");
            return;
        }
        Integer index = verdict.index();
        if (index == null || index < 1 || index > entries.size()) {
            problems.add("verdict index " + index + " out of range 1.." + entries.size());
            return;
        }
        if (!seenIndices.add(index)) {
            problems.add("photo " + index + " has more than one verdict");
            return;
        }
        SidecarPhotoEntry entry = entries.get(index - 1);
        if (!entry.name().equals(verdict.name())) {
            problems.add("verdict " + index + " names '" + verdict.name()
                    + "' but photo " + index + " is '" + entry.name() + "'");
            return;
        }
        String action = orEmpty(verdict.action());
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

    private @Nullable RawResponse parse(Message response, List<String> problems) {
        String text = responseText(response);
        if (text.isBlank()) {
            problems.add("response carries no text content");
            return null;
        }
        try {
            RawResponse parsed = mapper.readValue(text, RawResponse.class);
            // A literal null document deserializes to null; the IDE binds the generic result to
            // the non-null type and can't see that.
            //noinspection ConstantValue
            if (parsed == null) {
                problems.add("response is not a JSON object");
            }
            return parsed;
        } catch (JacksonException e) {
            problems.add("response is not valid verdict JSON: " + e.getMessage());
            return null;
        }
    }

    private static String responseText(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }

    private static CullException retryFailedException(String scope, String montage,
            List<String> firstProblems, List<String> retryProblems) {
        return new CullException("Cull for " + scope + " failed at " + montage
                + " and a corrective retry did not fix it."
                + "\nFirst attempt (" + firstProblems.size() + " problem(s)):\n - "
                + String.join("\n - ", firstProblems)
                + "\nRetry (" + retryProblems.size() + " problem(s)):\n - "
                + String.join("\n - ", retryProblems));
    }

    // Assembles one montage's complete API request. The image block precedes the text turn per
    // Anthropic's vision guidance: models resolve references into an image better when the image
    // comes first. The schema rides along as a structured-output format, so the response text is
    // the verdict JSON itself, never prose around it. No sampling parameters - current Anthropic
    // models reject them outright. Thinking is always sent explicitly, never left to the model
    // generation's own default: disabled unless configured on, adaptive when it is.
    private static MessageCreateParams request(String model, boolean thinking, String systemPrompt,
            String userTurn, String imageBase64) {
        var builder = MessageCreateParams.builder()
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

    // The corrective retry replays the failed exchange on top of the original request. The model's
    // own reply comes back as an assistant turn, then the problem list asks for corrected JSON.
    // A blank reply is echoed as a placeholder, since the API rejects empty text blocks.
    private static MessageCreateParams retryRequest(MessageCreateParams request, String responseText,
            String correctionTurn) {
        return request.toBuilder()
                .addAssistantMessage(responseText.isBlank() ? "(empty response)" : responseText)
                .addUserMessage(correctionTurn)
                .build();
    }

    private static JsonOutputFormat.Schema schemaOf(Map<String, Object> schema) {
        var builder = JsonOutputFormat.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }

    // The montage image is this app's own prep output, so an unreadable one means the prep
    // directory is broken. That fails loud and unchecked, same as an unreadable sidecar.
    private static String montageImageBase64(Path prepDir, String montage) {
        Path imagePath = prepDir.resolve(montage + ".jpg");
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read montage image " + imagePath, e);
        }
    }

    private String requiredModel() {
        String model = settings.providerSettings().model();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("sluice.cull.provider-settings.model is not set; "
                    + "the 'anthropic' vision provider needs the model id to request");
        }
        return model;
    }

    // Package-private so the live verify can wrap the client this builds instead of its own.
    static AnthropicClient defaultClient(CullProviderSettings providerSettings) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Environment variable ANTHROPIC_API_KEY is not set; "
                    + "the 'anthropic' vision provider needs it to call the API");
        }
        var builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
        String endpoint = providerSettings.endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.baseUrl(endpoint);
        }
        // Transport failures (429/5xx/timeouts) are the SDK's own retry family: exponential
        // backoff honoring retry-after, separate from the one content retry above.
        Integer maxRetries = providerSettings.maxRetries();
        builder.maxRetries(maxRetries == null ? DEFAULT_TRANSPORT_RETRIES : maxRetries);
        return builder.build();
    }

    private static String shardNameFor(String montage) {
        return montage.replaceFirst("^montage-", "decisions-") + ".json";
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
