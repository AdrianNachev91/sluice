package photos.sluice.application.service;

import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.vision.JsonSiftPrepStore;
import photos.sluice.application.port.out.SiftPrepPort;
import photos.sluice.application.port.out.SiftProviderSettings;
import photos.sluice.application.port.out.SiftSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.sift.ApplyReport;
import photos.sluice.domain.sift.SiftCategory;
import photos.sluice.domain.sift.Decision;
import photos.sluice.domain.sift.DecisionShard;
import photos.sluice.domain.sift.MontageConfig;
import photos.sluice.domain.sift.PrepDir;
import photos.sluice.domain.sift.SidecarPhotoEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

// Shared fixtures for every test that drives a real prep directory. It writes the on-disk
// artifacts a sift produces, and builds the services that read them. The apply side spans several
// collaborators, so a test usually needs two or three of them wired to the same roots.
//
// Everything writes through the real adapters against a @TempDir, so the assertions are about what
// actually lands on disk.
final class SiftPrepTestSupport {

    // The field separator a hand-written fixture log has to use. Read off MoveLedger rather than
    // restated, since it is a control character no test source can spell readably. Nothing here
    // asserts on the delimiter itself, so a fixture that follows the real format is exactly right.
    private static final String RECORD_DELIMITER = MoveLedger.RECORD_DELIMITER;

    private SiftPrepTestSupport() {
    }

    static Path prepDir(final Path root) throws IOException {
        final Path dir = root.resolve("logs/sift-prep/scope1");
        Files.createDirectories(dir);
        return dir;
    }

    static PrepDir readIndex(final Path prepDir) {
        return new JsonSiftPrepStore().readIndex(prepDir);
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
    static void writeIndex(final Path prepDir, final List<SiftCategory> categories, final int photos,
                           final List<Path> unreviewable, final List<String> entries) {
        new PrepIndexWriter().write(prepDir.resolve("index.json"),
                new PrepDir("2019-06", categories, prepDir.resolve("base"), photos, unreviewable, entries.size(),
                        prepDir, entries));
    }

    static List<SiftCategory> fixedCategories() {
        return fixedSettings().activeCategories();
    }

    // For a test whose subject is which names a run recorded. The description is filled in so the
    // card is well-formed, never because its text matters to the assertion.
    static List<SiftCategory> cards(final String... names) {
        return Arrays.stream(names).map(name -> SiftCategory.of(name, name + " description")).toList();
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

    static PathsConfig pathsConfig(final Path workingRoot, final Path libraryRoot) {
        return SettingsFixture.pathsConfig(workingRoot, libraryRoot, workingRoot.resolve("Inbox"));
    }

    static SiftSettings fixedSettings() {
        return new FixedSettings("external-agent", List.of(
                SiftCategory.of("scenery", "scenery description"),
                SiftCategory.of("food", "food description"),
                SiftCategory.of("funny", "funny description")));
    }

    static ApplyEngine applyEngine(final Path workingRoot, final Path libraryRoot) {
        return applyEngine(workingRoot, libraryRoot, hashIndex(workingRoot));
    }

    static ApplyEngine applyEngine(final Path workingRoot, final Path libraryRoot, final CsvLibraryHashIndex hashIndex) {
        return applyEngine(workingRoot, libraryRoot, hashIndex, new NioMediaStore());
    }

    static ApplyEngine applyEngine(final Path workingRoot, final Path libraryRoot, final CsvLibraryHashIndex hashIndex,
                                   final MediaStore mediaStore) {
        // One paths config for both, as in production, where they take the one bean. The engine's
        // destination refusals and the planner's source refusals must be measuring the same roots.
        final PathsConfig paths = pathsConfig(workingRoot, libraryRoot);
        return new ApplyEngine(mediaStore, new JsonSiftPrepStore(), new Sha256Hasher(), hashIndex,
                new SiftDestinations(paths), moveLedger(mediaStore), applyPlanner(paths, mediaStore));
    }

    static MoveLedger moveLedger(final MediaStore mediaStore) {
        return new MoveLedger(mediaStore, new DisasterDrawer(mediaStore));
    }

    static ApplyPlanner applyPlanner(final Path workingRoot) {
        return applyPlanner(SettingsFixture.workingRoot(workingRoot), new NioMediaStore());
    }

    static ApplyPlanner applyPlanner(final Path workingRoot, final MediaStore mediaStore) {
        return applyPlanner(SettingsFixture.workingRoot(workingRoot), mediaStore);
    }

    // A real NioMediaStore backs every other read, so the sidecar and shard reads this port covers
    // are the only ones a test can fail.
    static ApplyPlanner applyPlanner(final Path workingRoot, final SiftPrepPort siftPrepPort) {
        return applyPlanner(SettingsFixture.workingRoot(workingRoot), new NioMediaStore(), siftPrepPort);
    }

    static ApplyPlanner applyPlanner(final PathsPort paths, final MediaStore mediaStore) {
        return applyPlanner(paths, mediaStore, new JsonSiftPrepStore());
    }

    static ApplyPlanner applyPlanner(final PathsPort paths, final MediaStore mediaStore,
                                     final SiftPrepPort siftPrepPort) {
        return new ApplyPlanner(mediaStore, siftPrepPort, new Sha256Hasher(), paths);
    }

    // A caller takes the ledger snapshot and passes it into ApplyPlanner. A test driving the
    // planner directly reads the real (usually empty) on-disk state the same way a production
    // caller would, rather than fabricating a Ledger by hand.
    static MoveLedger.Ledger readLedger(final Path prepDir) {
        return moveLedger(new NioMediaStore()).read(prepDir);
    }

    static ReconcileEngine reconcileEngine(final Path workingRoot, final Path libraryRoot) {
        final var mediaStore = new NioMediaStore();
        final PathsConfig paths = pathsConfig(workingRoot, libraryRoot);
        return new ReconcileEngine(mediaStore, new JsonSiftPrepStore(), new Sha256Hasher(),
                new DisasterDrawer(mediaStore), new SiftDestinations(paths),
                moveLedger(mediaStore), applyPlanner(paths, mediaStore));
    }

    static PrepDirRemedies prepDirRemedies(final Path workingRoot, final Path libraryRoot) {
        return prepDirRemedies(workingRoot, libraryRoot, new JsonSiftPrepStore());
    }

    static PrepDirRemedies prepDirRemedies(final Path workingRoot, final Path libraryRoot, final SiftPrepPort siftPrepPort) {
        final var mediaStore = new NioMediaStore();
        return new PrepDirRemedies(mediaStore, siftPrepPort, pathsConfig(workingRoot, libraryRoot), fixedSettings(),
                new DisasterDrawer(mediaStore), moveLedger(mediaStore));
    }

    static PrepDirDoctor prepDirDoctor(final Path workingRoot) {
        return prepDirDoctor(workingRoot, new JsonSiftPrepStore());
    }

    // A read failure is injected at a seam this code owns rather than through the filesystem.
    // Putting a directory where a file belongs fails at the open on Windows and at the first read
    // on Linux. Those are two different code paths in the reader.
    //
    // The planner reads through the same port, matching production, where both take the one bean.
    // Handing it a separate real store would leave every read past the index working normally, so
    // only an index-read failure could ever be injected.
    static PrepDirDoctor prepDirDoctor(final Path workingRoot, final SiftPrepPort siftPrepPort) {
        final var mediaStore = new NioMediaStore();
        return new PrepDirDoctor(siftPrepPort, mediaStore,
                applyPlanner(SettingsFixture.workingRoot(workingRoot), mediaStore, siftPrepPort),
                moveLedger(mediaStore));
    }

    // Same seam as the overload above, on the other port. The injected store is threaded into the
    // planner and the ledger too, matching production, where all three take the one bean. Building
    // those with a fresh real store instead would leave the injected failure unreachable from
    // everything except the doctor's own direct calls.
    static PrepDirDoctor prepDirDoctor(final Path workingRoot, final MediaStore mediaStore) {
        final var siftPrepPort = new JsonSiftPrepStore();
        return new PrepDirDoctor(siftPrepPort, mediaStore,
                applyPlanner(SettingsFixture.workingRoot(workingRoot), mediaStore, siftPrepPort),
                moveLedger(mediaStore));
    }

    static Troubleshooter troubleshooter(final Path workingRoot, final Path libraryRoot) {
        final var mediaStore = new NioMediaStore();
        return new Troubleshooter(prepDirDoctor(workingRoot), reconcileEngine(workingRoot, libraryRoot),
                prepDirRemedies(workingRoot, libraryRoot), new DisasterDrawer(mediaStore));
    }

    static CsvLibraryHashIndex hashIndex(final Path workingRoot) {
        return new CsvLibraryHashIndex(SettingsFixture.workingRoot(workingRoot));
    }

    private record FixedSettings(String provider, List<SiftCategory> categories) implements SiftSettings {

        @Override
        public SiftProviderSettings providerSettings() {
            return SiftProviderSettings.unset();
        }

        @Override
        public SiftProviderSettings providerSettings(final String providerId) {
            return SiftProviderSettings.unset();
        }

        @Override
        public MontageConfig montage() {
            return MontageConfig.defaults();
        }
    }

    // Passes every read and write through to a real store. Only readSidecar is overridden, the one
    // call this exists to fail: a read that merely failed, with the content otherwise intact.
    static final class FailingSidecarRead implements SiftPrepPort {

        private final SiftPrepPort delegate = new JsonSiftPrepStore();

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
