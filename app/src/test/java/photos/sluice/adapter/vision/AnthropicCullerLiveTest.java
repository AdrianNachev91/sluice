package photos.sluice.adapter.vision;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.services.blocking.MessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.imaging.CullMontageRenderer;
import photos.sluice.adapter.imaging.MontageBuilder;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.imaging.TileRenderer;
import photos.sluice.adapter.secrets.TieredSecretStore;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.HeifDecoder;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.WatchMode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

// Live verify against the real Anthropic API. Skipped unless both gates hold: SLUICE_LIVE_CULL=true
// opts in explicitly, and ANTHROPIC_API_KEY carries a key. The double gate keeps a normal test run
// from spending API money just because a key happens to be set in the environment.
//
// One run proves the two things the mocked tests cannot. The live API accepts the montage request
// shape: image block, photo table, JSON-schema structured output, the structured-output schema. And it
// accepts the corrective-retry conversation: assistant echo of the failed reply plus a correction
// turn. A decorator forces the retry by flipping one filename in the first live response. The name
// check then fails, and the genuine second request goes out. Verdict content is deliberately not
// asserted - models vary. Schema validity and request acceptance are the contract here. A model
// that answers the retry with invalid content still fails the run loud. That is accepted for an
// opt-in smoke test.
//
// The montage is real: four distinct synthetic photos run through the full imaging pipeline, so the
// API sees exactly what a production cull sends. Cost per run is a fraction of a cent.
@EnabledIfEnvironmentVariable(named = "SLUICE_LIVE_CULL", matches = "true")
// The key gate wants a value carrying something other than whitespace. A variable holding only
// spaces counts as unset everywhere else in the app. Matching on it would enable this test and then
// fail it for want of a key. The pattern matches the whole value, so it has to allow the
// surrounding whitespace a real key can arrive with rather than demand none.
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = "(?s).*\\S.*")
class AnthropicCullerLiveTest {

    private static final String MODEL = "claude-sonnet-5";
    // Four photos in rows of two, so the sheet is small enough to eyeball against the model's
    // verdicts when this test is run by hand.
    private static final MontageConfig LIVE_GRID = new MontageConfig(224, 2);
    private static final List<String> PHOTO_NAMES = List.of(
            "IMG_20190601_100000.jpg", "IMG_20190602_100000.jpg",
            "IMG_20190603_100000.jpg", "IMG_20190604_100000.jpg");
    private static final List<CullCategory> CARDS = List.of(
            new CullCategory("junk", "Objectively worthless photos: blurry, accidental shots, "
                    + "screenshots, documents, photos of a screen."),
            new CullCategory("scenery", "Unremarkable scenery with no people and weak composition."));

    // cull() is strictly sequential, so plain fields suffice for the decorator's bookkeeping.
    private int liveCalls;
    private boolean tampered;

    @Test
    void cullsARealMontageAndSurvivesAForcedContentRetry(@TempDir final Path root) throws Exception {
        final PrepDir prep = renderRealMontage(root);
        assertThat(prep.entries()).containsExactly("montage-001");
        final CullSettings settings = settings();
        // Wrapping the production-built client exercises the whole real path: the key read through
        // the machine's own credential tiers, the absent endpoint override, and the transport-retry
        // knob. The environment tier answers first, so the file tier's directory is never reached.
        final AnthropicClient real = AnthropicCuller.defaultClient(settings.providerSettings("anthropic"),
                TieredSecretStore.forMachine(System::getenv, System.getProperty("os.name"),
                        root.resolve("secrets")));
        final var culler = new AnthropicCuller(new CullerPrompt(settings),
                new ShardCodec(), new SidecarReader(), settings, () -> this.tamperingClient(real),
                () -> {
                    throw new AssertionError("this test checks no credential");
                });

        final CullReport report = culler.cull(prep, new CullOptions(false, null));

        assertThat(this.liveCalls).isEqualTo(2);
        assertThat(this.tampered).isTrue();
        assertThat(report.montagesCulled()).isEqualTo(1);
        assertThat(report.montagesSkipped()).isZero();
        assertThat(report.inputTokens()).isPositive();
        assertThat(report.outputTokens()).isPositive();
        final DecisionShard shard = new ShardCodec().read(prep.prepDir().resolve("decisions-001.json"));
        assertThat(shard.montage()).isEqualTo("montage-001");
    }

    // Four distinct-colored photos through the real pipeline: one 2x2 sheet at production tile size.
    private static PrepDir renderRealMontage(final Path root) throws IOException {
        final var pathsConfig = SettingsFixture.pathsConfig(root, root.resolve("Library"), root.resolve("Inbox"));
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        final List<Color> colors = List.of(Color.RED, Color.GREEN, Color.BLUE, Color.ORANGE);
        for (int i = 0; i < PHOTO_NAMES.size(); i++) {
            writePhoto(juneDir, PHOTO_NAMES.get(i), colors.get(i));
        }
        final HeifDecoder stubHeifDecoder = _ -> Optional.empty();
        final var renderer = new CullMontageRenderer(
                new TileRenderer(stubHeifDecoder), new MontageBuilder(), new SidecarWriter(),
                new PrepIndexWriter(), new NioMediaStore(), pathsConfig, settings());
        return renderer.build(new CullScope.Year(2019, List.of(6)), LIVE_GRID);
    }

    private static void writePhoto(final Path dir, final String name, final Color color) throws IOException {
        Files.createDirectories(dir);
        final var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, 800, 600);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "jpg", dir.resolve(name).toFile());
    }

    // Wraps the real client so the first response comes back with one filename flipped. The culler's
    // name check then fails, and its corrective retry goes to the live API for real. Every other
    // method delegates untouched, including close().
    private AnthropicClient tamperingClient(final AnthropicClient real) {
        final MessageService tamperingMessages = mock(MessageService.class, delegatesTo(real.messages()));
        doAnswer(invocation -> {
            final Message response = real.messages().create(invocation.<MessageCreateParams>getArgument(0));
            return ++this.liveCalls == 1 ? this.tamper(response) : response;
        }).when(tamperingMessages).create(any(MessageCreateParams.class));
        final AnthropicClient wrapper = mock(AnthropicClient.class, delegatesTo(real));
        doReturn(tamperingMessages).when(wrapper).messages();
        return wrapper;
    }

    // Flips the first sidecar filename found in the response text. When the model already misnamed
    // every photo on its own, there is nothing to flip. The natural failure then forces the retry,
    // and the tampered flag stays false. That fails the test visibly enough to investigate.
    private Message tamper(final Message response) {
        final String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
        for (final String name : PHOTO_NAMES) {
            if (text.contains(name)) {
                this.tampered = true;
                final String flipped = text.replaceFirst(Pattern.quote(name), "TAMPERED_0001.jpg");
                return response.toBuilder()
                        .content(List.of(ContentBlock.ofText(TextBlock.builder()
                                .text(flipped)
                                .citations(List.of())
                                .build())))
                        .build();
            }
        }
        return response;
    }

    private static CullSettings settings() {
        return new FixedSettings("anthropic", CARDS, new CullProviderSettings(MODEL, null, null));
    }

    private record FixedSettings(String provider, List<CullCategory> categories,
                                 CullProviderSettings providerSettings) implements CullSettings {

        @Override
        public CullProviderSettings providerSettings(final String providerId) {
            return this.provider.equals(providerId) ? this.providerSettings : CullProviderSettings.unset();
        }

        @Override
        public ExternalAgentSettings externalAgent() {
            return new ExternalAgentSettings(WatchMode.MANUAL);
        }

        // The same grid renderRealMontage() lays the sheet out on, so the prompt describes the
        // montage the model is actually looking at.
        @Override
        public MontageConfig montage() {
            return LIVE_GRID;
        }
    }
}
