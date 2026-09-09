package photos.sluice.application.service;

import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

// Shared fixtures for every test that drives a real prep directory. It writes the on-disk
// artifacts a cull produces, and builds the services that read them. The apply side spans several
// collaborators, so a test usually needs two or three of them wired to the same roots.
//
// Everything writes through the real adapters against a @TempDir, so the assertions are about what
// actually lands on disk.
final class CullPrepTestSupport {

    // The field separator a hand-written fixture log has to use. Read off MoveLedger rather than
    // restated, since it is a control character no test source can spell readably. Nothing here
    // asserts on the delimiter itself, so a fixture that follows the real format is exactly right.
    private static final String RECORD_DELIMITER = MoveLedger.RECORD_DELIMITER;

    private CullPrepTestSupport() {
    }

    static Path prepDir(final Path root) throws IOException {
        final Path dir = root.resolve("logs/sift-prep/scope1");
        Files.createDirectories(dir);
        return dir;
    }

    static PrepDir readIndex(final Path prepDir) {
        return new JsonCullPrepStore().readIndex(prepDir);
    }

    static void writeIndex(final Path prepDir, final int photos, final List<String> entries) {
        writeIndex(prepDir, photos, List.of(), entries);
    }

    static void writeIndex(final Path prepDir, final int photos, final List<Path> unreviewable,
                           final List<String> entries) {
        writeIndex(prepDir, fixedCategories(), photos, unreviewable, entries);
    }

    // The category set an index records, defaulted to the one fixedSettings() prepares a run under
    // so the two agree unless a test deliberately pulls them apart.
    static void writeIndex(final Path prepDir, final List<CullCategory> categories, final int photos,
                           final List<Path> unreviewable, final List<String> entries) {
        new PrepIndexWriter().write(prepDir.resolve("index.json"),
                new PrepDir("2019-06", categories, prepDir.resolve("base"), photos, unreviewable, entries.size(),
                        prepDir, entries));
    }

    static List<CullCategory> fixedCategories() {
        return fixedSettings().activeCategories();
    }

    // For a test whose subject is which names a run recorded. The description is filled in so the
    // card is well-formed, never because its text matters to the assertion.
    static List<CullCategory> cards(final String... names) {
        return Arrays.stream(names).map(name -> CullCategory.of(name, name + " description")).toList();
    }

    static void writeSidecar(final Path prepDir, final String montage, final SidecarPhotoEntry... photos) {
        new SidecarWriter().write(prepDir.resolve(montage + ".json"), prepDir.resolve(montage + ".jpg"),
                List.of(photos));
    }

    static SidecarPhotoEntry sidecarEntry(final Path src) {
        return new SidecarPhotoEntry(src, src.getFileName().toString(), Instant.parse("2019-06-15T10:00:00Z"), false);
    }

    // Simulates a move-record line an earlier, crashed run would have written before its move.
    // Pairs with a hand-placed destination file standing in for that move having actually happened.
    static void writeMoveRecord(final Path prepDir, final Path source, final Path dest, final String hash) throws IOException {
        Files.writeString(prepDir.resolve("move-records.log"),
                source + RECORD_DELIMITER + dest + RECORD_DELIMITER + hash + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static void writeShard(final Path prepDir, final String montage, final String... decisionsJson) throws IOException {
        final String shardName = montage.replaceFirst("^montage-", "decisions-") + ".json";
        Files.writeString(prepDir.resolve(shardName),
                "{ \"montage\": \"%s\", \"decisions\": [ %s ] }".formatted(montage, String.join(", ", decisionsJson)));
    }

    static String classificationJson(final Path file, final String category, final String reason) {
        return "{ \"file\": \"%s\", \"action\": \"%s\", \"reason\": \"%s\" }".formatted(jsonEscaped(file), category,
                reason);
    }

    static String keepJson(final Path file) {
        return "{ \"file\": \"%s\", \"action\": \"keep\" }".formatted(jsonEscaped(file));
    }

    static String nearDupChosenJson(final Path file, final String group, final String chosenReason) {
        return "{ \"file\": \"%s\", \"action\": \"near-dup-chosen\", \"group\": \"%s\", \"chosen_reason\": \"%s\" }"
                .formatted(jsonEscaped(file), group, chosenReason);
    }

    static String nearDupRejectJson(final Path file, final String group, final String reason) {
        return "{ \"file\": \"%s\", \"action\": \"near-dup-reject\", \"group\": \"%s\", \"reason\": \"%s\" }"
                .formatted(jsonEscaped(file), group, reason);
    }

    static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // What a torn write or a damaged sector leaves behind, as opposed to a merely malformed line.
    // 0xFF and 0xFE are not legal UTF-8 lead bytes, so decoding fails outright rather than yielding
    // a line anything could try to parse.
    static void writeUndecodable(final Path file) throws IOException {
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFF});
    }

    static PathsConfig pathsConfig(final Path repoRoot, final Path libraryRoot) {
        return SettingsFixture.pathsConfig(repoRoot, libraryRoot, repoRoot.resolve("Inbox"));
    }

    static CullSettings fixedSettings() {
        return new FixedSettings("external-agent", List.of(
                CullCategory.of("scenery", "scenery description"),
                CullCategory.of("food", "food description"),
                CullCategory.of("funny", "funny description")));
    }

    static ApplyEngine applyEngine(final Path repoRoot, final Path libraryRoot) {
        return applyEngine(repoRoot, libraryRoot, hashIndex(repoRoot));
    }

    static ApplyEngine applyEngine(final Path repoRoot, final Path libraryRoot, final CsvLibraryHashIndex hashIndex) {
        return applyEngine(repoRoot, libraryRoot, hashIndex, new NioMediaStore());
    }

    static ApplyEngine applyEngine(final Path repoRoot, final Path libraryRoot, final CsvLibraryHashIndex hashIndex,
                                   final MediaStore mediaStore) {
        // One paths config for both, as in production, where they take the one bean. The engine's
        // destination refusals and the planner's source refusals must be measuring the same roots.
        final PathsConfig paths = pathsConfig(repoRoot, libraryRoot);
        return new ApplyEngine(mediaStore, new JsonCullPrepStore(), new Sha256Hasher(), hashIndex,
                new CullDestinations(paths), moveLedger(mediaStore), applyPlanner(paths, mediaStore));
    }

    static MoveLedger moveLedger(final MediaStore mediaStore) {
        return new MoveLedger(mediaStore, new DisasterDrawer(mediaStore));
    }

    static ApplyPlanner applyPlanner(final Path repoRoot) {
        return applyPlanner(SettingsFixture.workingRoot(repoRoot), new NioMediaStore());
    }

    static ApplyPlanner applyPlanner(final Path repoRoot, final MediaStore mediaStore) {
        return applyPlanner(SettingsFixture.workingRoot(repoRoot), mediaStore);
    }

    // A real NioMediaStore backs every other read, so the sidecar and shard reads this port covers
    // are the only ones a test can fail.
    static ApplyPlanner applyPlanner(final Path repoRoot, final CullPrepPort cullPrepPort) {
        return applyPlanner(SettingsFixture.workingRoot(repoRoot), new NioMediaStore(), cullPrepPort);
    }

    static ApplyPlanner applyPlanner(final PathsPort paths, final MediaStore mediaStore) {
        return applyPlanner(paths, mediaStore, new JsonCullPrepStore());
    }

    static ApplyPlanner applyPlanner(final PathsPort paths, final MediaStore mediaStore,
                                     final CullPrepPort cullPrepPort) {
        return new ApplyPlanner(mediaStore, cullPrepPort, new Sha256Hasher(), paths);
    }

    // A caller takes the ledger snapshot and passes it into ApplyPlanner. A test driving the
    // planner directly reads the real (usually empty) on-disk state the same way a production
    // caller would, rather than fabricating a Ledger by hand.
    static MoveLedger.Ledger readLedger(final Path prepDir) {
        return moveLedger(new NioMediaStore()).read(prepDir);
    }

    static ReconcileEngine reconcileEngine(final Path repoRoot, final Path libraryRoot) {
        final var mediaStore = new NioMediaStore();
        final PathsConfig paths = pathsConfig(repoRoot, libraryRoot);
        return new ReconcileEngine(mediaStore, new JsonCullPrepStore(), new Sha256Hasher(),
                new DisasterDrawer(mediaStore), new CullDestinations(paths),
                moveLedger(mediaStore), applyPlanner(paths, mediaStore));
    }

    static PrepDirRemedies prepDirRemedies(final Path repoRoot, final Path libraryRoot) {
        return prepDirRemedies(repoRoot, libraryRoot, new JsonCullPrepStore());
    }

    static PrepDirRemedies prepDirRemedies(final Path repoRoot, final Path libraryRoot, final CullPrepPort cullPrepPort) {
        final var mediaStore = new NioMediaStore();
        return new PrepDirRemedies(mediaStore, cullPrepPort, pathsConfig(repoRoot, libraryRoot), fixedSettings(),
                new DisasterDrawer(mediaStore), moveLedger(mediaStore));
    }

    static PrepDirDoctor prepDirDoctor(final Path repoRoot) {
        return prepDirDoctor(repoRoot, new JsonCullPrepStore());
    }

    // A read failure is injected at a seam this code owns rather than through the filesystem.
    // Putting a directory where a file belongs fails at the open on Windows and at the first read
    // on Linux. Those are two different code paths in the reader.
    //
    // The planner reads through the same port, matching production, where both take the one bean.
    // Handing it a separate real store would leave every read past the index working normally, so
    // only an index-read failure could ever be injected.
    static PrepDirDoctor prepDirDoctor(final Path repoRoot, final CullPrepPort cullPrepPort) {
        final var mediaStore = new NioMediaStore();
        return new PrepDirDoctor(cullPrepPort, mediaStore,
                applyPlanner(SettingsFixture.workingRoot(repoRoot), mediaStore, cullPrepPort),
                moveLedger(mediaStore));
    }

    // Same seam as the overload above, on the other port. The injected store is threaded into the
    // planner and the ledger too, matching production, where all three take the one bean. Building
    // those with a fresh real store instead would leave the injected failure unreachable from
    // everything except the doctor's own direct calls.
    static PrepDirDoctor prepDirDoctor(final Path repoRoot, final MediaStore mediaStore) {
        final var cullPrepPort = new JsonCullPrepStore();
        return new PrepDirDoctor(cullPrepPort, mediaStore,
                applyPlanner(SettingsFixture.workingRoot(repoRoot), mediaStore, cullPrepPort),
                moveLedger(mediaStore));
    }

    static Troubleshooter troubleshooter(final Path repoRoot, final Path libraryRoot) {
        final var mediaStore = new NioMediaStore();
        return new Troubleshooter(prepDirDoctor(repoRoot), reconcileEngine(repoRoot, libraryRoot),
                prepDirRemedies(repoRoot, libraryRoot), new DisasterDrawer(mediaStore));
    }

    static CsvLibraryHashIndex hashIndex(final Path repoRoot) {
        return new CsvLibraryHashIndex(SettingsFixture.workingRoot(repoRoot));
    }

    private record FixedSettings(String provider, List<CullCategory> categories) implements CullSettings {

        @Override
        public CullProviderSettings providerSettings() {
            return CullProviderSettings.unset();
        }

        @Override
        public CullProviderSettings providerSettings(final String providerId) {
            return CullProviderSettings.unset();
        }

        @Override
        public MontageConfig montage() {
            return MontageConfig.defaults();
        }
    }

    // Passes every read and write through to a real store. Only readSidecar is overridden, the one
    // call this exists to fail: a read that merely failed, with the content otherwise intact.
    static final class FailingSidecarRead implements CullPrepPort {

        private final CullPrepPort delegate = new JsonCullPrepStore();

        @Override
        public PrepDir readIndex(final Path prepDir) {
            return this.delegate.readIndex(prepDir);
        }

        @Override
        public void writeIndex(final Path prepDir, final PrepDir index) {
            this.delegate.writeIndex(prepDir, index);
        }

        @Override
        public List<SidecarPhotoEntry> readSidecar(final Path prepDir, final String montage) {
            throw new UncheckedIOException(new IOException("simulated read failure"));
        }

        @Override
        public boolean hasShard(final Path prepDir, final String montage) {
            return this.delegate.hasShard(prepDir, montage);
        }

        @Override
        public DecisionShard readShard(final Path prepDir, final String montage) {
            return this.delegate.readShard(prepDir, montage);
        }

        @Override
        public DecisionShard readShardFile(final Path shardFile) {
            return this.delegate.readShardFile(shardFile);
        }

        @Override
        public void writeMergedDecisions(final Path prepDir, final String scope, final List<Decision> decisions,
                                         final ApplyReport report) {
            this.delegate.writeMergedDecisions(prepDir, scope, decisions, report);
        }
    }
}
