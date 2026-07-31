package photos.sluice.application.service;

import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.job.WatchMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

// Shared fixtures for every test that drives a real prep directory. It writes the on-disk artifacts
// a cull produces - index.json, sidecars, shards, a move-record log - and builds the services that
// read them. The apply side spans several collaborators, so a test usually needs two or three of
// them wired to the same roots. Building that graph in one place keeps each test file about
// behavior rather than construction.
//
// Everything writes through the REAL adapters against a @TempDir, not fakes. These tests are about
// what actually lands on disk, so a stubbed store would prove far less.
final class CullPrepTestSupport {

    // The field separator a hand-written fixture log has to use. Read off MoveLedger rather than
    // restated, since it is a control character no test source can spell readably. Nothing here
    // asserts on the delimiter itself, so a fixture that follows the real format is exactly right.
    private static final String RECORD_DELIMITER = MoveLedger.RECORD_DELIMITER;

    private CullPrepTestSupport() {
    }

    static Path prepDir(final Path root) throws IOException {
        final Path dir = root.resolve("logs/cull-prep/scope1");
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
        new PrepIndexWriter().write(prepDir.resolve("index.json"),
                new PrepDir("2019-06", prepDir.resolve("base"), photos, unreviewable, entries.size(), prepDir,
                        entries));
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
        return new PathsConfig(
                new PathsProperties(repoRoot.toString(), libraryRoot.toString(), repoRoot.resolve("Inbox").toString()));
    }

    static CullSettings fixedSettings() {
        return new FixedSettings("external-agent", List.of(
                new CullCategory("junk", "junk description"),
                new CullCategory("scenery", "scenery description"),
                new CullCategory("food", "food description"),
                new CullCategory("funny", "funny description")));
    }

    static ApplyEngine applyEngine(final Path repoRoot, final Path libraryRoot) {
        return applyEngine(repoRoot, libraryRoot, hashIndex(repoRoot));
    }

    static ApplyEngine applyEngine(final Path repoRoot, final Path libraryRoot, final CsvLibraryHashIndex hashIndex) {
        return applyEngine(repoRoot, libraryRoot, hashIndex, new NioMediaStore());
    }

    static ApplyEngine applyEngine(final Path repoRoot, final Path libraryRoot, final CsvLibraryHashIndex hashIndex,
                                   final MediaStore mediaStore) {
        return new ApplyEngine(mediaStore, new JsonCullPrepStore(), new Sha256Hasher(), hashIndex,
                new CullDestinations(pathsConfig(repoRoot, libraryRoot)), moveLedger(mediaStore),
                applyPlanner(mediaStore));
    }

    static MoveLedger moveLedger(final MediaStore mediaStore) {
        return new MoveLedger(mediaStore, new DisasterDrawer(mediaStore));
    }

    static ApplyPlanner applyPlanner() {
        return applyPlanner(new NioMediaStore());
    }

    static ApplyPlanner applyPlanner(final MediaStore mediaStore) {
        return new ApplyPlanner(mediaStore, new JsonCullPrepStore(), fixedSettings(), new Sha256Hasher());
    }

    // A caller takes the ledger snapshot and passes it into ApplyPlanner. A test driving the
    // planner directly reads the real (usually empty) on-disk state the same way a production
    // caller would, rather than fabricating a Ledger by hand.
    static MoveLedger.Ledger readLedger(final Path prepDir) {
        return moveLedger(new NioMediaStore()).read(prepDir);
    }

    static ReconcileEngine reconcileEngine(final Path repoRoot, final Path libraryRoot) {
        final var mediaStore = new NioMediaStore();
        return new ReconcileEngine(mediaStore, new JsonCullPrepStore(), new Sha256Hasher(),
                new DisasterDrawer(mediaStore), new CullDestinations(pathsConfig(repoRoot, libraryRoot)),
                moveLedger(mediaStore), applyPlanner(mediaStore));
    }

    static PrepDirRemedies prepDirRemedies(final Path repoRoot, final Path libraryRoot) {
        final var mediaStore = new NioMediaStore();
        return new PrepDirRemedies(mediaStore, new JsonCullPrepStore(), pathsConfig(repoRoot, libraryRoot),
                new DisasterDrawer(mediaStore), moveLedger(mediaStore));
    }

    static PrepDirDoctor prepDirDoctor() {
        final var mediaStore = new NioMediaStore();
        return new PrepDirDoctor(new JsonCullPrepStore(), mediaStore, fixedSettings(), applyPlanner(),
                moveLedger(mediaStore));
    }

    static Troubleshooter troubleshooter(final Path repoRoot, final Path libraryRoot) {
        final var mediaStore = new NioMediaStore();
        return new Troubleshooter(prepDirDoctor(), reconcileEngine(repoRoot, libraryRoot),
                prepDirRemedies(repoRoot, libraryRoot), new DisasterDrawer(mediaStore));
    }

    static CsvLibraryHashIndex hashIndex(final Path repoRoot) {
        return new CsvLibraryHashIndex(repoRoot.resolve("logs/library-hashes.csv"));
    }

    private record FixedSettings(String provider, List<CullCategory> categories) implements CullSettings {

        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }

        @Override
        public ExternalAgentSettings externalAgent() {
            return new ExternalAgentSettings(WatchMode.MANUAL, null);
        }
    }
}
