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
// problem and throws checked CullException naming the montage and every problem found. Missing
// connection settings (model id, API key) are the user's configuration to fix and fail unchecked
// with the property or variable name. A prep directory whose sidecar or montage image can't be
// read is broken app output. That fails unchecked too, because the scope needs re-prepping.
//
// The API client is built lazily inside cull(), never at startup, so the app boots without an API
// key for users on other providers. The factory seam exists for tests to inject a mock client.
@Component
class AnthropicCuller implements VisionCuller {

    private static final long MAX_TOKENS = 8192;
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

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public CullReport cull(PrepDir prep, CullOptions opts) throws CullException {
        String model = requiredModel();
        String systemPrompt = prompt.systemPrompt();
        List<String> categoryNames = settings.categories().stream().map(CullCategory::name).toList();
        long inputTokens = 0;
        long outputTokens = 0;
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
                MessageCreateParams request = request(model, systemPrompt,
                        prompt.userTurn(prep.scope(), montage, ordinal, total, entries),
                        montageImageBase64(prep.prepDir(), montage));
                Message response = client.messages().create(request);
                DecisionShard shard = shardOf(prep.scope(), montage, entries, response);
                acceptedShards.add(new ShardFile(montage, shard));
                entries.stream().map(SidecarPhotoEntry::src).forEach(acceptedSrcs::add);
                ValidationReport report = validator.validate(acceptedShards, acceptedSrcs, categoryNames);
                failOnProblems(prep.scope(), montage, report.problems());
                shardCodec.write(prep.prepDir().resolve(shardNameFor(montage)), shard);
                inputTokens += response.usage().inputTokens();
                outputTokens += response.usage().outputTokens();
            }
        } finally {
            client.close();
        }
        return new CullReport(total, 0, inputTokens, outputTokens);
    }

    // Turns one montage's response into a decision shard, or throws with every response-level
    // problem found. Only what exists solely in the response is checked here: tile-index coverage
    // and the name match. Everything shard-shaped stays with ShardValidator, the contract's single
    // source of truth, which the caller runs over the accumulated set.
    private DecisionShard shardOf(String scope, String montage, List<SidecarPhotoEntry> entries,
            Message response) throws CullException {
        RawResponse parsed = parse(scope, montage, response);
        var problems = new ArrayList<String>();
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
        failOnProblems(scope, montage, problems);
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

    private RawResponse parse(String scope, String montage, Message response) throws CullException {
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw cullException(scope, montage, List.of("response carries no text content"));
        }
        try {
            RawResponse parsed = mapper.readValue(text, RawResponse.class);
            // A literal null document deserializes to null; the IDE binds the generic result to
            // the non-null type and can't see that.
            //noinspection ConstantValue
            if (parsed == null) {
                throw cullException(scope, montage, List.of("response is not a JSON object"));
            }
            return parsed;
        } catch (JacksonException e) {
            throw cullException(scope, montage, List.of("response is not valid verdict JSON: "
                    + e.getMessage()));
        }
    }

    private static void failOnProblems(String scope, String montage, List<String> problems)
            throws CullException {
        if (!problems.isEmpty()) {
            throw cullException(scope, montage, problems);
        }
    }

    private static CullException cullException(String scope, String montage, List<String> problems) {
        return new CullException("Cull for " + scope + " failed at " + montage + " ("
                + problems.size() + " problem(s)):\n - " + String.join("\n - ", problems));
    }

    // Assembles one montage's complete API request. The image block precedes the text turn per
    // Anthropic's vision guidance: models resolve references into an image better when the image
    // comes first. The schema rides along as a structured-output format, so the response text is
    // the verdict JSON itself, never prose around it. No sampling parameters - current Anthropic
    // models reject them outright. MAX_TOKENS bounds the response; a full verdict list for one
    // sheet fits comfortably within it.
    private static MessageCreateParams request(String model, String systemPrompt, String userTurn,
            String imageBase64) {
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
                                .schema(RESPONSE_SCHEMA)
                                .build())
                        .build())
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

    private static AnthropicClient defaultClient(CullProviderSettings providerSettings) {
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
        return builder.build();
    }

    private static String shardNameFor(String montage) {
        return montage.replaceFirst("^montage-", "decisions-") + ".json";
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
