package photos.sluice.adapter.vision;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.services.blocking.MessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.imaging.CullMontageRenderer;
import photos.sluice.adapter.imaging.MontageBuilder;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.imaging.TileRenderer;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.HeifDecoder;
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.secrets.SecretStore;

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

// Live verify against the real Anthropic API. Skipped unless both gates hold: the run opts in
// explicitly, and a key is reachable. Only the first guards spending, since nobody opts in by
// accident. The second is so a machine with no key skips rather than going red.
//
// Opting in takes either SLUICE_LIVE_CULL=true in the environment or -Dsluice.live.cull=true on
// the command line. The property is there because an environment variable cannot be set for a
// single Maven invocation on every shell this project is run from. Pair it with -Dtest= to reach
// one test: the forecast and credential checks cost nothing, and only the montage cull spends.
//
// One run proves the two things the mocked tests cannot. The live API accepts the montage request
// shape, meaning the image block, the photo table and the structured-output schema. And it accepts
// the corrective-retry conversation, meaning an assistant echo of the failed reply plus a
// correction turn. A decorator forces that retry by flipping one filename in the first live
// response, so the name check fails and a genuine second request goes out.
//
// Verdict content is deliberately not asserted, models being what they are. Schema validity and
// request acceptance are the contract. A model answering the retry with invalid content still
// fails the run loud, which is accepted for an opt-in smoke test.
//
// The montage is real: four distinct synthetic photos run through the full imaging pipeline, so
// the API sees exactly what a production cull sends. Cost per run is a fraction of a cent.
@EnabledIf("liveRunIsPossible")
class AnthropicCullerLiveTest {

    private static final String MODEL = "claude-sonnet-5";
    // Four photos in rows of two, so the sheet is small enough to eyeball against the model's
    // verdicts when this test is run by hand.
    private static final MontageConfig LIVE_GRID = new MontageConfig(224, 2);
    private static final List<String> PHOTO_NAMES = List.of(
            "IMG_20190601_100000.jpg", "IMG_20190602_100000.jpg",
            "IMG_20190603_100000.jpg", "IMG_20190604_100000.jpg");
    private static final List<CullCategory> CARDS = List.of(
            CullCategory.of("junk", "Objectively worthless photos: blurry, accidental shots, "
                    + "screenshots, documents, photos of a screen."),
            CullCategory.of("scenery", "Unremarkable scenery with no people and weak composition."));

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
                machineStore(root.resolve("secrets")));
        final var culler = new AnthropicCuller(new CullerPrompt(settings),
                new ShardCodec(), new SidecarReader(), settings, () -> this.tamperingClient(real),
                _ -> {
                    throw new AssertionError("this test checks no credential");
                });

        final CullReport report = culler.cull(prep, CullOptions.unbounded(false));

        assertThat(this.liveCalls).isEqualTo(2);
        assertThat(this.tampered).isTrue();
        assertThat(report.montagesCulled()).isEqualTo(1);
        assertThat(report.montagesSkipped()).isZero();
        assertThat(report.apiCalls()).isEqualTo(2);
        assertThat(report.spend().inputTokens()).isPositive();
        assertThat(report.spend().outputTokens()).isPositive();
        final DecisionShard shard = new ShardCodec().read(prep.prepDir().resolve("decisions-001.json"));
        assertThat(shard.montage()).isEqualTo("montage-001");
    }

    // The whole spend ceiling rests on the counting route accepting a body with an outputConfig on
    // it. A mocked test can only prove the params object carries one. If the real route refuses it,
    // forecast() answers Unknown, every ceiling silently falls back to the shipped seed, and one
    // log line is the only trace.
    //
    // Costs nothing. Anthropic does not bill the counting route, and it generates no tokens.
    @Test
    void countsWhatARealMontageWouldSendWithoutSendingIt(@TempDir final Path root) throws Exception {
        final PrepDir prep = renderRealMontage(root);
        final CullSettings settings = settings();
        final var culler = new AnthropicCuller(new CullerPrompt(settings), new ShardCodec(),
                new SidecarReader(), settings,
                () -> AnthropicCuller.defaultClient(settings.providerSettings("anthropic"),
                        machineStore(root.resolve("secrets"))),
                _ -> {
                    throw new AssertionError("this test checks no credential");
                });

        final long startedAt = System.nanoTime();
        final SpendForecast forecast = culler.forecast(prep);
        final long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        // The wall clock is printed because it is the cost this call is judged on. It buys the
        // ceiling, and it is paid once per run against a vision pass of many calls.
        //noinspection UseOfSystemOutOrSystemErr
        System.out.printf("[live-forecast] %s in %dms%n", forecast, elapsedMillis);
        assertThat(forecast)
                .as("the counting route accepted a request carrying a structured-output schema")
                .isInstanceOf(SpendForecast.Counted.class);
        // A sheet plus its photo table plus the schema. A figure near zero would mean the count
        // route accepted a body stripped of the parts the paid call is actually charged for.
        assertThat(((SpendForecast.Counted) forecast).inputTokensPerCall()).isGreaterThan(1_000);
    }

    // The second thing mocks cannot answer: whether the Models API really carries the capability
    // flags offerable() filters on, and whether a real account's list survives that filter. Both
    // are assumptions this adapter was built on and neither has met the service.
    //
    // Costs nothing. Listing models generates no tokens, which is why the credential check was
    // built on it rather than on a one-token message.
    @Test
    void checksARealCredentialAndListsWhatTheAccountCanRun(@TempDir final Path root) {
        final CullSettings settings = settings();
        final var culler = new AnthropicCuller(new CullerPrompt(settings), new ShardCodec(),
                new SidecarReader(), settings, machineStore(root.resolve("secrets")));

        final ProviderCheck outcome = culler.check();

        // Printed rather than only asserted. The point of this run is seeing what the service
        // actually returns, and an assertion that passes says nothing about the shape.
        //noinspection UseOfSystemOutOrSystemErr
        System.out.printf("[live-check] %s%n", outcome);
        assertThat(outcome)
                .as("a working key over an account with usable models")
                .isInstanceOf(ProviderCheck.Accepted.class);
        final ModelCatalog models = ((ProviderCheck.Accepted) outcome).models();
        assertThat(models.options()).isNotEmpty();
        assertThat(models.options()).allSatisfy(option -> {
            assertThat(option.id()).isNotBlank();
            assertThat(option.label()).isNotBlank();
        });
    }

    // Rejected is the outcome the Test button most often shows a user, through a typo'd or expired
    // key. A mocked UnauthorizedException is the only thing that has proven it so far.
    //
    // The key itself never touches the real environment. The sibling live tests here need
    // ANTHROPIC_API_KEY to hold a working key. A store that read it would answer with theirs
    // rather than the revoked one. So this store is built with no environment tier, and on an
    // operating system no keyring is written for. The file it is handed is then the only tier a
    // lookup can reach.
    @Test
    void aRevokedKeyIsAnsweredAsRejected(@TempDir final Path root) throws IOException {
        final SecretStore revoked = SecretStore.forApplication("Sluice")
                .inNamespace("photos.sluice")
                .withCredentialFilesIn(root.resolve("secrets"))
                .onOperatingSystem("no-keyring")
                .open();
        revoked.save(AnthropicCuller.API_KEY, revokedKeyFixture());
        final CullSettings settings = settings();
        final var culler = new AnthropicCuller(new CullerPrompt(settings), new ShardCodec(),
                new SidecarReader(), settings, revoked);

        final ProviderCheck outcome = culler.check();

        //noinspection UseOfSystemOutOrSystemErr
        System.out.printf("[live-check-rejected] %s%n", outcome);
        assertThat(outcome).isEqualTo(new ProviderCheck.Rejected());
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

    // Revoked the same day it was drawn from a real account. Reading it here costs no money and
    // needs no fresh credential. A rejected key answers the same way whether it was ever valid or
    // made up, as long as the service has never seen it accepted.
    private static String revokedKeyFixture() throws IOException {
        return Files.readString(Path.of("..", "tools", "anthropic-api-key.txt")).strip();
    }

    private static CullSettings settings() {
        return new FixedSettings("anthropic", CARDS, new CullProviderSettings(MODEL, null, null));
    }

    // Both gates, as one condition because @EnabledIf does not repeat.
    static boolean liveRunIsPossible() {
        return liveRunRequested() && aKeyIsReachable();
    }

    // Two routes rather than one because CI carries the variable but no command line.
    private static boolean liveRunRequested() {
        return "true".equals(System.getenv("SLUICE_LIVE_CULL"))
                || Boolean.getBoolean("sluice.live.cull");
    }

    // Asks the same three tiers the test's own client reads: environment, then this machine's
    // keyring, then a file. A gate naming the environment variable alone would skip a machine
    // holding its key where Settings puts it, however deliberately the run was opted into.
    //
    // Answers false for anything that goes wrong rather than propagating. A condition method that
    // throws fails the class instead of skipping it, and this one touches a platform keyring on
    // whatever runner it lands on. A missing Secret Service is a reason to skip, never a red build.
    private static boolean aKeyIsReachable() {
        try {
            return machineStore(Path.of(System.getProperty("java.io.tmpdir"), "sluice-live-gate"))
                    .secret(AnthropicCuller.API_KEY)
                    .isPresent();
        } catch (final RuntimeException | LinkageError unreachable) {
            return false;
        }
    }

    // The same three tiers AppConfig composes, named the same way. The secrets directory differs.
    // So a machine holding its key only in a file is the one case where this reads nothing and a
    // running Sluice reads a key.
    private static SecretStore machineStore(final Path secretsDir) {
        return SecretStore.forApplication("Sluice")
                .inNamespace("photos.sluice")
                .withEnvironmentOverride()
                .withCredentialFilesIn(secretsDir)
                .onOperatingSystem(System.getProperty("os.name"))
                .open();
    }

    private record FixedSettings(String provider, List<CullCategory> categories,
                                 CullProviderSettings providerSettings) implements CullSettings {

        @Override
        public CullProviderSettings providerSettings(final String providerId) {
            return this.provider.equals(providerId) ? this.providerSettings : CullProviderSettings.unset();
        }

        // The same grid renderRealMontage() lays the sheet out on, so the prompt describes the
        // montage the model is actually looking at.
        @Override
        public MontageConfig montage() {
            return LIVE_GRID;
        }
    }
}
