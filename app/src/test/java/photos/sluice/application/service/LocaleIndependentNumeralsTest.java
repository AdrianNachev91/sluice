package photos.sluice.application.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.InboxScanner;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.ImageDimensionsReader;
import photos.sluice.adapter.metadata.ExifSource;
import photos.sluice.adapter.metadata.FilenameSource;
import photos.sluice.adapter.metadata.MtimeSource;
import photos.sluice.adapter.metadata.TakeoutJsonSource;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.CullScopeSelector;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.model.Numerals;
import photos.sluice.domain.model.SortScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JVM's default {@code FORMAT} locale for every test here to a regional Arabic locale
 * whose native numeral set is not ASCII (finding #6). A bare {@code ar} locale still resolves to
 * plain Latin digits under the CLDR provider. Only a regional variant like {@code ar-SA} actually
 * picks the non-ASCII digit set, so that is the one pinned below.
 *
 * <p>Every assertion checks that a path segment, id, or prep-dir tag built from a year, month, or
 * sequence number still comes out ASCII under that pinned locale. The round trip additionally
 * proves the concrete failure this defect causes. A year-scoped commit silently reports zero
 * files. {@code CommitScopeSelector}'s {@code \d} pattern is ASCII-only. It never matches a
 * Sorted folder name written in the wrong numeral system.
 */
class LocaleIndependentNumeralsTest {

    private Locale originalFormatLocale;

    @BeforeEach
    void pinNonAsciiFormatLocale() {
        this.originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));
    }

    @AfterEach
    void restoreFormatLocale() {
        Locale.setDefault(Locale.Category.FORMAT, this.originalFormatLocale);
    }

    @Test
    void numeralsPaddedStaysAscii() {
        assertThat(Numerals.padded(2021, 4)).isEqualTo("2021");
        assertThat(Numerals.padded(3, 2)).isEqualTo("03");
        assertThat(Numerals.padded(7, 3)).isEqualTo("007");
    }

    @Test
    void cullScopeTagStaysAscii() {
        assertThat(CullScope.tag(new CullScope.Year(2021, List.of(3)))).isEqualTo("2021-03");
    }

    @Test
    void cullScopeSelectorMonthDirectoryStaysAscii() {
        final var selector = new CullScopeSelector();
        final Path photosRoot = Path.of("Sorted", "Photos");

        final var directories = selector.directoriesToScan(photosRoot, new CullScope.Year(2021, List.of(3)));

        assertThat(directories).containsExactly(photosRoot.resolve("2021").resolve("03"));
    }

    @Test
    void montageIdForStaysAscii() {
        assertThat(MontageNaming.montageIdFor(7)).isEqualTo("montage-007");
    }

    @Test
    void sortThenYearScopedCommitMovesTheFile(@TempDir final Path root) throws IOException {
        final Path inbox = root.resolve("Inbox");
        writeFile(inbox.resolve("20210315_photo.jpg"), padded("keeper"));
        final Path libraryRoot = root.resolve("Library");

        this.sortEngine(root).sort(new SortScope.OldestYear());
        final CommitSummary summary = this.commitEngine(root, libraryRoot).commit(new CommitScope.Year(2021, null));

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(Files.exists(libraryRoot.resolve("Photos/2021/03/20210315_photo.jpg"))).isTrue();
    }

    private SortEngine sortEngine(final Path root) {
        final var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), root.toString(), root.resolve("Inbox").toString()));
        final var dateResolver =
                new DateResolver(new TakeoutJsonSource(), new ExifSource(), new FilenameSource(), new MtimeSource());
        return new SortEngine(pathsConfig, new InboxScanner(), dateResolver, new Sha256Hasher(),
                new CsvLibraryHashIndex(root.resolve("logs").resolve("library-hashes.csv")),
                new ImageDimensionsReader(), new NioMediaStore());
    }

    private CommitEngine commitEngine(final Path root, final Path libraryRoot) {
        final var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), libraryRoot.toString(), root.resolve("Inbox").toString()));
        return new CommitEngine(pathsConfig, new NioMediaStore(), new Sha256Hasher(),
                new CsvLibraryHashIndex(root.resolve("logs").resolve("library-hashes.csv")));
    }

    private static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Mirrors SortEngineTest's own fixture: 60,000 bytes clears LowResGate's threshold, so this
    // test's sort/commit outcome isn't entangled with low-res routing.
    private static String padded(final String marker) {
        return marker + "x".repeat(60_000);
    }
}
