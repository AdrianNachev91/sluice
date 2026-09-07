package photos.sluice.adapter.ui;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import photos.sluice.adapter.cli.RefusalClassifier;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.LibraryRootMoveNeedsAResolutionException;
import photos.sluice.application.port.in.NoteIsNotTextException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.RunsUnreadableException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.in.UnfinishedRunsException;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.port.out.MalformedSettingsException;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.StaleSecretNotClearedException;
import photos.sluice.application.port.out.TransferAbandonedException;
import photos.sluice.application.port.out.UnrecognisedProviderException;
import photos.sluice.application.port.out.UnusableSettingsException;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.model.SortSummary.Guessed;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Nothing the compiler checks relates the refusals the ports raise to the arms that word them. The
// refusal set is a Throwable hierarchy, so widening it breaks no switch. This is the check that
// stands in for one, over both surfaces at once.
//
// The scan behind it reaches application.port and Pipeline's nested types. A refusal declared as a
// top-level class in application.service would be invisible to it, and none is today.
class RefusalCoverageTest {

    private final RefusalClassifier classifier = new RefusalClassifier(noCredentialsAnywhere());

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void theDesktopWordsEveryRefusalItCanMeet(final Refusal refusal) {
        if (refusal.desktop().wordedHere()) {
            assertThat(RunRefusals.said(refusal.thrown()).sentence())
                    .as("the desktop words %s with somebody else's arm", refusal.name())
                    .doesNotContain(String.valueOf(refusal.thrown()))
                    .contains(refusal.desktop().says());
        } else {
            assertThat(refusal.desktop().reason())
                    .as("%s is not worded on the desktop and says no reason why", refusal.name())
                    .isNotBlank();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void theCommandLineWordsEveryRefusalItCanMeet(final Refusal refusal) {
        if (refusal.commandLine().wordedHere()) {
            assertThat(this.classifier.refusalFor(refusal.thrown()))
                    .as("the command line has no arm for %s", refusal.name())
                    .isNotNull();
        } else {
            assertThat(refusal.commandLine().reason())
                    .as("%s is not worded on the command line and says no reason why", refusal.name())
                    .isNotBlank();
        }
    }

    // The one that makes the two above self-maintaining. Everything else here passes happily while a
    // refusal type nobody thought about reaches a reader as a class name.
    @Test
    void everyRefusalTypeThePortsRaiseIsAccountedFor() {
        final Set<String> tabled = refusals().map(refusal -> refusal.thrown().getClass().getName())
                .collect(Collectors.toSet());
        final Set<String> raised = refusalTypesOnTheClasspath();

        // A scan that found nothing lets the line below pass over an empty set. That is the one way
        // this test can look green while checking nothing at all.
        assertThat(raised).as("the scan found no refusal types, so it is looking in the wrong place")
                .hasSizeGreaterThan(20);
        assertThat(tabled)
                .as("a refusal type the table does not name. Add it, on both surfaces, "
                        + "or say there why that surface cannot meet it")
                .containsAll(raised);
    }

    // A job's failure arrives through JobHandle.failureIn, and what that hands over decides which
    // arm fires. Every other test here calls said() with an exception built in the test, which is
    // exactly why an unwrap that stripped a real level went unnoticed.
    @Test
    void aTypedRefusalThrownInsideAJobKeepsItsOwnArm() {
        final var note = Path.of("Review", "2019-06", "_reasons.txt");
        final var thrown = new NoteIsNotTextException(note,
                new UncheckedIOException(new MalformedInputException(1)));

        final String said = RunRefusals.said(deliveredByAJobThatThrew(thrown)).sentence();

        assertThat(said).contains(note.toString());
    }

    @Test
    void aJobsFailureIsUnwrappedOnlyAsFarAsTheMachineryWrappedIt() {
        final var refusal = new JobInProgressException("Something is running.");

        assertThat(deliveredByAJobThatThrew(new CompletionException(refusal))).isSameAs(refusal);
        assertThat(deliveredByAJobThatThrew(refusal)).isSameAs(refusal);
    }

    // Every refusal type the ports can raise, and what each surface does with it. The instance is
    // what gets fed through, so a type whose arm reads a field is exercised with that field set.
    private static Stream<Refusal> refusals() {
        return Stream.of(
                // The four that carry their own sentence through, so the fragment is the message.
                worded(new JobInProgressException("Something is running."), "Something is running."),
                worded(new ShuttingDownException("Sluice is closing."), "Sluice is closing."),
                worded(new ImportSourceException("That folder cannot be imported from."),
                        "That folder cannot be imported from."),
                worded(new Pipeline.RunAlreadyFinishedException(aPrepDir()),
                        "finished before it could be discarded"),

                worded(new PathsMisconfiguredException(
                        List.of(new PathViolation.NotConfigured(PathRole.LIBRARY_ROOT))),
                        "cannot be used"),
                worded(new MissingCredentialException(anthropicKey(), "No key stored."),
                        "your provider key is not set"),
                worded(new SecretStoreException(SecretStoreException.Tier.KEYRING, "The store refused."),
                        "credential store on this computer"),
                worded(new Pipeline.ScopeOccupiedException(aRun("2019")), "has not finished"),
                worded(new Pipeline.CurateConflictException(aRun("2019"), aSortThatFilledIt()),
                        "has not finished"),
                worded(new Pipeline.ScopeOverlapsException(new CullScope.Year(2019, null),
                        List.of(aRun("2019-06"))), "overlaps"),
                worded(new Pipeline.NothingToRedoException(aPrepDir()), "waiting to be judged again"),
                worded(new Pipeline.ScopeUnreadableException(aPrepDir(), new IOException("held")),
                        "Cannot determine the sifts for this timeframe"),
                worded(new Pipeline.RunOutsideWorkingRootException(aPrepDir()),
                        "not inside the folders currently saved"),

                // These four all reach the UncheckedIOException family. Each one's fragment is what
                // proves its own arm fired rather than a broader one below it.
                worded(new MalformedPrepJsonException("index.json is damaged", new IOException("bad")),
                        "records could not be read"),
                worded(new NoteIsNotTextException(Path.of("Review", "2019-06", "_reasons.txt"),
                        new UncheckedIOException(new MalformedInputException(1))),
                        "_reasons.txt"),
                worded(new UncheckedIOException(new MalformedInputException(1)),
                        "other than text"),
                worded(new UncheckedIOException(new IOException("the drive stopped answering")),
                        "could not be reached"),

                worded(new ApplyException("the answers do not hold",
                        List.of(new Finding.MissingReason("montage-001", 0))),
                        "do not hold together"),
                worded(new UnrecognisedProviderException("antropic", Set.of("anthropic", "external-agent")),
                        "There is no vision provider called 'antropic'"),

                // Read ahead of both switches, because it is not a refusal.
                elsewhere(new CullException("sheet 3 came back wrong twice"),
                        "RunLauncherPresenter.cardFor and CommandReports.reading read it first"),

                // A save that moves a folder root claims the new one. So the command line meets this
                // as a verb a caller ran, and the desktop meets it under Save.
                new Refusal(new WorkingRootBusyException(Path.of("D:", "Photos", "Sluice")),
                        Handling.unreachable("SettingsRefusals words it under the Save button"),
                        Handling.recognised()),

                new Refusal(new MalformedSettingsException(aConfigFile(), "not valid YAML"),
                        Handling.unreachable("SettingsRefusals words it under the Save button"),
                        Handling.unreachable("no verb reads or writes settings; startup words it")),
                new Refusal(new UnusableSettingsException("More than the 12 categories allowed."),
                        Handling.unreachable("SettingsRefusals words it under the Save button"),
                        Handling.unreachable("no verb reads or writes settings; startup words it")),
                new Refusal(new StaleSecretNotClearedException("the old key is still in the keyring"),
                        Handling.unreachable("VisionProviderPresenter catches it on the key card"),
                        Handling.unreachable("no verb writes a credential")),

                new Refusal(new LibraryRootMoveNeedsAResolutionException(aLibraryRoot(), "asks first"),
                        Handling.unreachable("SettingsPresenter.save catches it and opens the dialog"),
                        Handling.unreachable("no verb moves the library root")),
                new Refusal(new RunsUnreadableException(aPrepDir()),
                        Handling.unreachable("raised inside the move, which words it from its own message"),
                        Handling.unreachable("no verb moves the library root")),
                new Refusal(new UnfinishedRunsException("Finish or discard them first.", List.of("2019")),
                        Handling.unreachable("raised inside the move, which words it from its own message"),
                        Handling.unreachable("no verb moves the library root")),

                // Raised by a transfer given up on mid-file.
                new Refusal(new TransferAbandonedException(Path.of("Inbox", "IMG_1234.jpg")),
                        Handling.unreachable("every engine that can raise it catches it"),
                        Handling.unreachable("every engine that can raise it catches it")));
    }

    // What a whenComplete on a real JobHandle hands its listener, rather than what a test assumes it
    // does. The handle completes its own future directly, so nothing wraps what the job threw.
    private static Throwable deliveredByAJobThatThrew(final Throwable thrown) {
        return JobHandle.failureIn(thrown);
    }

    private static Set<String> refusalTypesOnTheClasspath() {
        final JavaClasses ports = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("photos.sluice.application.port", "photos.sluice.application.service");
        return ports.stream()
                .filter(RefusalCoverageTest::isARefusalType)
                .map(JavaClass::getName)
                .collect(Collectors.toSet());
    }

    // A refusal type is one this app declared and named, which is what a surface can match on. The
    // service package holds many classes and only Pipeline's nested refusals are among them.
    private static boolean isARefusalType(final JavaClass candidate) {
        return candidate.isAssignableTo(Throwable.class)
                && !candidate.getModifiers().contains(JavaModifier.ABSTRACT)
                && (candidate.getPackageName().startsWith("photos.sluice.application.port")
                    || candidate.getName().startsWith(Pipeline.class.getName() + "$"));
    }

    // The fragment pins the desktop only. That is where this chunk changed what reaches a switch,
    // and where a deleted arm falls through to a broader one that answers plausibly and wrongly. The
    // command line's own classifier has its type-family ordering pinned by its own tests, so here it
    // is asked only whether it recognises the type at all.
    private static Refusal worded(final Throwable thrown, final String desktopSays) {
        return new Refusal(thrown, Handling.words(desktopSays), Handling.recognised());
    }

    private static Refusal elsewhere(final Throwable thrown, final String where) {
        return new Refusal(thrown, Handling.unreachable(where), Handling.unreachable(where));
    }

    private static CullRunSummary aRun(final String scope) {
        return new CullRunSummary(scope, Path.of("logs", "sift-prep", scope),
                new PrepDirHealth(State.WAITING, List.of()), null, Instant.EPOCH);
    }

    private static SortSummary aSortThatFilledIt() {
        return new SortSummary(3, 0, 0, 2, 1, 0, 0, 0, List.of(), Guessed.NONE, List.of(),
                Set.of(2019), List.of(), false, 0);
    }

    private static Path aPrepDir() {
        return Path.of("logs", "sift-prep", "2019");
    }

    private static Path aConfigFile() {
        return Path.of("AppData", "Sluice", "config.yml");
    }

    private static Path aLibraryRoot() {
        return Path.of("Library");
    }

    private static SecretId anthropicKey() {
        return new SecretId("anthropic", "SLUICE_ANTHROPIC_API_KEY");
    }

    // One refusal type, and what each of the two surfaces does with it.
    private record Refusal(Throwable thrown, Handling desktop, Handling commandLine) {

        String name() {
            return this.thrown.getClass().getSimpleName();
        }

        @Override
        public String toString() {
            return this.name();
        }
    }

    // Either the surface words it, or it cannot get there and says why. There is no third answer,
    // and "we have not looked" is not one of them.
    //
    // A worded row carries a fragment of the sentence it should produce. Asserting only that the
    // catch-all did not fire cannot tell this type's own arm from a broader arm above it. Several of
    // these extend UncheckedIOException, so deleting one arm sends it to another that answers with a
    // plausible, wrong sentence, and no test notices.
    //
    // That the catch-all did not fire is asked of the throwable rather than of the sentence it
    // writes. Quoting the raw type is the one thing only the catch-all does, and it stays true
    // however that sentence is reworded.
    private record Handling(boolean wordedHere, String says, String reason) {

        private static Handling words(final String says) {
            return new Handling(true, says, "");
        }

        private static Handling recognised() {
            return new Handling(true, "", "");
        }

        private static Handling unreachable(final String reason) {
            return new Handling(false, "", reason);
        }
    }

    // The classifier asks where a credential could sit, when one is missing. Nothing here is about
    // credentials, so it answers that no place holds one.
    private static SecretStore noCredentialsAnywhere() {
        final SecretStore store = mock(SecretStore.class);
        when(store.holdings(anthropicKey())).thenReturn(List.of());
        return store;
    }
}
