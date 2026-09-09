package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginProvider;
import photos.sluice.SluiceApplication;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.startup.StartupFailure;
import photos.sluice.application.startup.StartupFailure.ConfigPosition;
import photos.sluice.application.startup.StartupFailure.RejectedSetting;
import photos.sluice.application.startup.StartupFailure.Unclassified;
import photos.sluice.application.startup.StartupFailure.UnparsableConfigFile;
import photos.sluice.application.startup.StartupFailure.UnusableSettings;
import photos.sluice.application.startup.StartupFailure.WorkingRootBusy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// The failures here are raised by really starting the app, not by hand-built exception chains. What
// this class reads is the shape Spring and snakeyaml happen to throw, so a fixture built from what
// the code expects would prove nothing about either.
class SpringStartupFailureClassifierTest {

    @Test
    void aValueTheAppRefusesNamesTheSettingAndWhereItSits(@TempDir final Path dir) throws IOException {
        final Path configFile = dir.resolve("config.yml");
        Files.writeString(configFile, """
                sluice:
                  montage:
                    tile-size: many
                """);

        final StartupFailure failure = classify(configFile, startWith(configFile));

        assertThat(failure).isInstanceOfSatisfying(RejectedSetting.class, rejected -> {
            assertThat(rejected.property()).isEqualTo("sluice.montage.tile-size");
            assertThat(rejected.spot()).isNotNull();
            assertThat(rejected.spot().file()).isEqualTo(configFile);
            assertThat(rejected.spot().position()).isEqualTo(new ConfigPosition(3, 16));
        });
    }

    // The file holds a good value for the same setting, so the argument is the only thing left that
    // can be refused. Without it, an implementation reading the file's own place for any refusal at
    // all would pass this.
    @Test
    void aValueRefusedFromTheCommandLineNamesNoPlaceInTheFile(@TempDir final Path dir) throws IOException {
        final Path configFile = dir.resolve("config.yml");
        Files.writeString(configFile, """
                sluice:
                  montage:
                    tile-size: 96
                """);

        final StartupFailure failure = classify(configFile,
                startWith(configFile, "--sluice.montage.tile-size=many"));

        assertThat(failure).isInstanceOfSatisfying(RejectedSetting.class, rejected -> {
            assertThat(rejected.property()).isEqualTo("sluice.montage.tile-size");
            assertThat(rejected.spot()).isNull();
        });
    }

    // It binds cleanly and is refused by the value built out of it. So nothing here is a
    // BindException, and there is no place in the file to point at.
    @Test
    void settingsTheAppItselfRefusesCarryTheirOwnSentence(@TempDir final Path dir) throws IOException {
        final Path configFile = dir.resolve("config.yml");
        Files.writeString(configFile, """
                sluice:
                  cull:
                    categories:
                      - name: junk
                        description: Screenshots and blurry shots.
                """);

        final StartupFailure failure = classify(configFile, startWith(configFile));

        assertThat(failure).isInstanceOfSatisfying(UnusableSettings.class, unusable ->
                assertThat(unusable.problem()).isEqualTo("Photo categories may not hold one called 'junk'. "
                        + "Sluice supplies that one itself, and it is always on."));
    }

    // Offering to edit a file is only honest for the file the user owns. A value the app rejects
    // can come from its own bundled defaults, which is a real file on disk and not theirs to edit.
    @Test
    void aValueFromSomeOtherFileNamesNoPlaceInTheUsersOwn(@TempDir final Path dir) throws IOException {
        final Path someOtherFile = dir.resolve("config.yml");
        Files.writeString(someOtherFile, """
                sluice:
                  montage:
                    tile-size: many
                """);

        final StartupFailure failure = classify(dir.resolve("mine.yml"), startWith(someOtherFile));

        assertThat(failure).isInstanceOfSatisfying(RejectedSetting.class,
                rejected -> assertThat(rejected.spot()).isNull());
    }

    @Test
    void aFileTheParserGivesUpOnNamesTheFileAndTheLine(@TempDir final Path dir) throws IOException {
        final Path configFile = dir.resolve("config.yml");
        Files.writeString(configFile, """
                sluice:
                  montage:
                    tile-size: [1, 2
                """);

        final StartupFailure failure = classify(configFile, startWith(configFile));

        assertThat(failure).isInstanceOfSatisfying(UnparsableConfigFile.class, unparsable -> {
            assertThat(unparsable.spot().file()).isEqualTo(configFile);
            assertThat(unparsable.spot().position()).isNotNull();
            assertThat(unparsable.problem()).contains("expected ',' or ']'");
        });
    }

    @Test
    void aBusyWorkingRootIsRecognisedWhereItIsThrown(@TempDir final Path dir) {
        final StartupFailure failure = classify(dir.resolve("config.yml"), new WorkingRootBusyException(dir));

        assertThat(failure).isInstanceOfSatisfying(WorkingRootBusy.class,
                busy -> assertThat(busy.workingRoot()).isEqualTo(dir));
    }

    @Test
    void aBusyWorkingRootIsRecognisedThroughAWrapper(@TempDir final Path dir) {
        final var wrapped = new IllegalStateException("could not run the startup sequence",
                new WorkingRootBusyException(dir));

        final StartupFailure failure = classify(dir.resolve("config.yml"), wrapped);

        assertThat(failure).isInstanceOfSatisfying(WorkingRootBusy.class,
                busy -> assertThat(busy.workingRoot()).isEqualTo(dir));
    }

    @Test
    void aFailureNothingRecognisesIsUnclassified(@TempDir final Path dir) {
        final StartupFailure failure = classify(dir.resolve("config.yml"),
                new IllegalStateException("the disk went away"));

        assertThat(failure).isInstanceOf(Unclassified.class);
    }

    @Test
    void everyAnswerCarriesTheTraceItWasClassifiedFrom(@TempDir final Path dir) {
        final StartupFailure failure = classify(dir.resolve("config.yml"),
                new IllegalStateException("the disk went away"));

        assertThat(failure.trace())
                .contains("java.lang.IllegalStateException: the disk went away")
                .contains("SpringStartupFailureClassifierTest");
    }

    @Test
    void aTraceCarriesTheWholeChainRatherThanTheTopOfIt(@TempDir final Path dir) {
        final var wrapped = new IllegalStateException("the outer one",
                new IllegalArgumentException("the buried one"));

        final StartupFailure failure = classify(dir.resolve("config.yml"), wrapped);

        assertThat(failure.trace()).contains("the outer one").contains("the buried one");
    }

    // Returning at all is the proof: an unbounded walk never reaches the assertion. The timeout
    // needs its own thread, since the default mode measures a test once it returns.
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    void aCauseChainThatLoopsBackOnItselfStillAnswers(@TempDir final Path dir) {
        final var inner = new IllegalStateException("the inner one");
        final var outer = new IllegalStateException("the outer one", inner);
        // Closes the loop. A cycle cannot be built out of constructors alone, since each end would
        // have to exist before the other.
        inner.initCause(outer);

        assertThat(classify(dir.resolve("config.yml"), outer)).isInstanceOf(Unclassified.class);
    }

    // A second walk with its own bound. Returning at all is the proof, and the timeout needs its
    // own thread to measure a method that never does.
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    void anOriginChainThatHoldsItselfStillAnswers() {
        final var looping = new LoopingOrigin();

        assertThat(SpringStartupFailureClassifier.textOrigin(looping)).isNull();
        assertThat(looping.asked).isGreaterThan(1);
    }

    private static StartupFailure classify(final Path configFile, final Throwable failure) {
        return new SpringStartupFailureClassifier(configFile).classify(failure);
    }

    // Starts the real app the way the desktop launcher does, and answers with whatever stopped it.
    private static Throwable startWith(final Path configFile, final String... extraArgs) {
        final Throwable thrown = catchThrowable(
                () -> new SpringApplicationBuilder(SluiceApplication.class)
                        .run(SpringLaunch.importing(configFile, extraArgs))
                        .close());
        assertThat(thrown).as("the app was expected not to start").isNotNull();
        return thrown;
    }

    // Holds itself both ways round, so a walk taking either route never reaches an end. It counts
    // what it was asked, which is what tells a bounded walk apart from one that stopped at the
    // first hop.
    private static final class LoopingOrigin implements Origin, OriginProvider {

        private int asked;

        @Override
        public Origin getOrigin() {
            this.asked++;
            return this;
        }

        @Override
        public Origin getParent() {
            return this;
        }
    }
}
