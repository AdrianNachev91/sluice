package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.SettingsFixture;
import photos.sluice.config.SettingsHolder;
import photos.sluice.domain.model.IndexEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvLibraryHashIndexTest {

    @Test
    void loadReturnsEmptyMapWhenIndexFileMissing(@TempDir final Path workingRoot) {
        final CsvLibraryHashIndex index = indexAt(workingRoot);

        assertThat(index.load()).isEmpty();
    }

    @Test
    void setAsideMovesTheIndexAndLeavesTheNextReadEmpty(@TempDir final Path workingRoot,
                                                        @TempDir final Path graveyard) {
        final CsvLibraryHashIndex index = indexAt(workingRoot);
        index.append(List.of(new IndexEntry("aaa", Path.of("holiday.jpg"))));
        final Path filedAt = graveyard.resolve("kept").resolve("library-hashes-2026-08-16.csv");

        assertThat(index.setAside(filedAt)).isTrue();

        assertThat(filedAt).isRegularFile().content().contains("aaa");
        assertThat(index.load()).isEmpty();
        assertThat(index.contains("aaa")).isFalse();
    }

    @Test
    void setAsideAnswersThatThereWasNoIndexToMove(@TempDir final Path workingRoot, @TempDir final Path graveyard) {
        final Path filedAt = graveyard.resolve("library-hashes-2026-08-16.csv");

        assertThat(indexAt(workingRoot).setAside(filedAt)).isFalse();

        assertThat(filedAt).doesNotExist();
    }

    // The index is what authorizes deleting an Inbox file as a copy already safe in the library. If
    // it kept answering out of the old working root after a save, it would vouch for a library the
    // user is no longer filing into.
    @Test
    void aReadOrWriteBegunAfterASavedWorkingRootUsesIt(@TempDir final Path before, @TempDir final Path after) {
        final var holder = new SettingsHolder(SettingsFixture.settings(
                new PathSettings(before.toString(), before.toString(), before.resolve("Inbox").toString())));
        final var index = new CsvLibraryHashIndex(new PathsConfig(holder));
        index.append(List.of(new IndexEntry("aaa", Path.of("before.jpg"))));

        holder.apply(SettingsFixture.settings(
                new PathSettings(after.toString(), after.toString(), after.resolve("Inbox").toString())));
        index.append(List.of(new IndexEntry("bbb", Path.of("after.jpg"))));

        assertThat(index.load()).containsOnlyKeys("bbb");
        assertThat(after.resolve("logs").resolve("library-hashes.csv")).isRegularFile();
    }

    // A session writes to the one file it opened against. Following a mid-session save would split
    // a single commit's rows across two indexes, so neither would account for the run.
    @Test
    void anOpenSessionKeepsWritingToTheWorkingRootItBeganOn(@TempDir final Path before, @TempDir final Path after)
            throws IOException {
        final var holder = new SettingsHolder(SettingsFixture.settings(
                new PathSettings(before.toString(), before.toString(), before.resolve("Inbox").toString())));
        final var index = new CsvLibraryHashIndex(new PathsConfig(holder));

        try (final HashIndexPort.Session session = index.openSession()) {
            session.append(new IndexEntry("aaa", Path.of("first.jpg")));
            holder.apply(SettingsFixture.settings(
                    new PathSettings(after.toString(), after.toString(), after.resolve("Inbox").toString())));
            session.append(new IndexEntry("bbb", Path.of("second.jpg")));
        }

        assertThat(Files.readAllLines(before.resolve("logs").resolve("library-hashes.csv"), StandardCharsets.UTF_8))
                .contains("\"aaa\",\"first.jpg\"", "\"bbb\",\"second.jpg\"");
        assertThat(after.resolve("logs")).doesNotExist();
    }

    @Test
    void loadParsesFixtureShapedLikeRealIndexWithBomAndDuplicateHash(@TempDir final Path workingRoot) throws IOException {
        final Path csv = workingRoot.resolve("logs").resolve("library-hashes.csv");
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, """
                ﻿"sha256","path"\r
                "201936E3F7331FE027E25C63481533A55C7BD9F2CF37A2664B4A4AA301CC19FE","D:\\OneDrive\\PhotoLibrary\\Photos\\2017\\08\\a.jpg"\r
                "201936E3F7331FE027E25C63481533A55C7BD9F2CF37A2664B4A4AA301CC19FE","D:\\OneDrive\\PhotoLibrary\\Photos\\2017\\08\\a (2).jpg"\r
                """, StandardCharsets.UTF_8);
        final CsvLibraryHashIndex index = indexAt(workingRoot);

        final Map<String, List<Path>> loaded = index.load();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get("201936E3F7331FE027E25C63481533A55C7BD9F2CF37A2664B4A4AA301CC19FE"))
                .containsExactly(
                        Path.of("D:\\OneDrive\\PhotoLibrary\\Photos\\2017\\08\\a.jpg"),
                        Path.of("D:\\OneDrive\\PhotoLibrary\\Photos\\2017\\08\\a (2).jpg"));
    }

    @Test
    void containsReflectsLoadedHashes(@TempDir final Path workingRoot) throws IOException {
        final Path csv = workingRoot.resolve("logs").resolve("library-hashes.csv");
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, """
                "sha256","path"
                "ABC123","D:\\lib\\x.jpg"
                """, StandardCharsets.UTF_8);
        final CsvLibraryHashIndex index = indexAt(workingRoot);

        assertThat(index.contains("ABC123")).isTrue();
        assertThat(index.contains("DOESNOTEXIST")).isFalse();
    }

    @Test
    void appendCreatesFileWithHeaderWhenMissing(@TempDir final Path workingRoot) {
        final CsvLibraryHashIndex index = indexAt(workingRoot);
        final Path entryPath = Path.of("D:\\lib\\one.jpg");

        index.append(List.of(new IndexEntry("HASH1", entryPath)));

        assertThat(index.load()).containsEntry("HASH1", List.of(entryPath));
    }

    @Test
    void appendAddsRowsWithoutDuplicatingHeaderOnExistingFile(@TempDir final Path workingRoot) throws IOException {
        final Path csv = workingRoot.resolve("logs").resolve("library-hashes.csv");
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, """
                "sha256","path"
                "HASH1","D:\\lib\\one.jpg"
                """, StandardCharsets.UTF_8);
        final CsvLibraryHashIndex index = indexAt(workingRoot);

        index.append(List.of(new IndexEntry("HASH2", Path.of("D:\\lib\\two.jpg"))));

        final List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertThat(lines).containsExactly(
                "\"sha256\",\"path\"",
                "\"HASH1\",\"D:\\lib\\one.jpg\"",
                "\"HASH2\",\"D:\\lib\\two.jpg\"");
    }

    @Test
    void appendInsertsMissingNewlineBeforeNewRowsWhenLastLineWasNotTerminated(@TempDir final Path workingRoot)
            throws IOException {
        final Path csv = workingRoot.resolve("logs").resolve("library-hashes.csv");
        Files.createDirectories(csv.getParent());
        // Deliberately no trailing newline after the last row.
        Files.writeString(csv, """
                "sha256","path"
                "HASH1","D:\\lib\\one.jpg\"""", StandardCharsets.UTF_8);
        final CsvLibraryHashIndex index = indexAt(workingRoot);
        final Path secondEntryPath = Path.of("D:\\lib\\two.jpg");

        index.append(List.of(new IndexEntry("HASH2", secondEntryPath)));

        final List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertThat(lines).containsExactly(
                "\"sha256\",\"path\"",
                "\"HASH1\",\"D:\\lib\\one.jpg\"",
                "\"HASH2\",\"D:\\lib\\two.jpg\"");
        assertThat(index.load()).containsOnly(
                Map.entry("HASH1", List.of(Path.of("D:\\lib\\one.jpg"))),
                Map.entry("HASH2", List.of(secondEntryPath)));
    }

    @Test
    void sessionAppendsSeveralEntriesUnderOneHeader(@TempDir final Path workingRoot) throws IOException {
        final Path csv = workingRoot.resolve("logs").resolve("library-hashes.csv");
        final CsvLibraryHashIndex index = indexAt(workingRoot);

        try (final HashIndexPort.Session session = index.openSession()) {
            session.append(new IndexEntry("HASH1", Path.of("D:\\lib\\one.jpg")));
            session.append(new IndexEntry("HASH2", Path.of("D:\\lib\\two.jpg")));
        }

        final List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertThat(lines).containsExactly(
                "\"sha256\",\"path\"",
                "\"HASH1\",\"D:\\lib\\one.jpg\"",
                "\"HASH2\",\"D:\\lib\\two.jpg\"");
    }

    @Test
    void sessionClosedWithoutAnyAppendLeavesIndexFileUntouched(@TempDir final Path workingRoot) {
        final Path csv = workingRoot.resolve("logs").resolve("library-hashes.csv");
        final CsvLibraryHashIndex index = indexAt(workingRoot);

        // Deliberately no append() call before closing - an empty commit/rescue scope.
        index.openSession().close();

        assertThat(Files.exists(csv)).isFalse();
    }

    @Test
    void sessionFlushesEachEntryImmediatelyRatherThanBufferingUntilClose(@TempDir final Path workingRoot) throws IOException {
        final Path csv = workingRoot.resolve("logs").resolve("library-hashes.csv");
        final CsvLibraryHashIndex index = indexAt(workingRoot);

        try (final HashIndexPort.Session session = index.openSession()) {
            session.append(new IndexEntry("HASH1", Path.of("D:\\lib\\one.jpg")));

            // Read back through a separate file handle before the session closes, which is what
            // proves the row reached disk rather than sitting in the open writer's buffer.
            final List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            assertThat(lines).containsExactly(
                    "\"sha256\",\"path\"",
                    "\"HASH1\",\"D:\\lib\\one.jpg\"");
        }
    }

    @Test
    void roundTripsCommaInPath(@TempDir final Path workingRoot) {
        final CsvLibraryHashIndex index = indexAt(workingRoot);
        final Path trickyPath = Path.of("D:\\lib\\a, b.jpg");

        index.append(List.of(new IndexEntry("HASH3", trickyPath)));

        assertThat(index.load()).containsEntry("HASH3", List.of(trickyPath));
    }

    private static CsvLibraryHashIndex indexAt(final Path workingRoot) {
        return new CsvLibraryHashIndex(SettingsFixture.workingRoot(workingRoot));
    }
}
