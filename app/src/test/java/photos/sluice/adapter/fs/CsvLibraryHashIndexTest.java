package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    void loadReturnsEmptyMapWhenIndexFileMissing(@TempDir Path repoRoot) {
        CsvLibraryHashIndex index = indexAt(repoRoot);

        assertThat(index.load()).isEmpty();
    }

    @Test
    void loadParsesFixtureShapedLikeRealIndexWithBomAndDuplicateHash(@TempDir Path repoRoot) throws IOException {
        Path csv = repoRoot.resolve("logs").resolve("library-hashes.csv");
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, """
                ﻿"sha256","path"\r
                "201936E3F7331FE027E25C63481533A55C7BD9F2CF37A2664B4A4AA301CC19FE","D:\\OneDrive\\PhotoLibrary\\Photos\\2017\\08\\a.jpg"\r
                "201936E3F7331FE027E25C63481533A55C7BD9F2CF37A2664B4A4AA301CC19FE","D:\\OneDrive\\PhotoLibrary\\Photos\\2017\\08\\a (2).jpg"\r
                """, StandardCharsets.UTF_8);
        CsvLibraryHashIndex index = indexAt(repoRoot);

        Map<String, List<Path>> loaded = index.load();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get("201936E3F7331FE027E25C63481533A55C7BD9F2CF37A2664B4A4AA301CC19FE"))
                .containsExactly(
                        Path.of("D:\\OneDrive\\PhotoLibrary\\Photos\\2017\\08\\a.jpg"),
                        Path.of("D:\\OneDrive\\PhotoLibrary\\Photos\\2017\\08\\a (2).jpg"));
    }

    @Test
    void containsReflectsLoadedHashes(@TempDir Path repoRoot) throws IOException {
        Path csv = repoRoot.resolve("logs").resolve("library-hashes.csv");
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, """
                "sha256","path"
                "ABC123","D:\\lib\\x.jpg"
                """, StandardCharsets.UTF_8);
        CsvLibraryHashIndex index = indexAt(repoRoot);

        assertThat(index.contains("ABC123")).isTrue();
        assertThat(index.contains("DOESNOTEXIST")).isFalse();
    }

    @Test
    void appendCreatesFileWithHeaderWhenMissing(@TempDir Path repoRoot) {
        CsvLibraryHashIndex index = indexAt(repoRoot);

        index.append(List.of(new IndexEntry("HASH1", Path.of("D:\\lib\\one.jpg"))));

        assertThat(index.load()).containsEntry("HASH1", List.of(Path.of("D:\\lib\\one.jpg")));
    }

    @Test
    void appendAddsRowsWithoutDuplicatingHeaderOnExistingFile(@TempDir Path repoRoot) throws IOException {
        Path csv = repoRoot.resolve("logs").resolve("library-hashes.csv");
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, """
                "sha256","path"
                "HASH1","D:\\lib\\one.jpg"
                """, StandardCharsets.UTF_8);
        CsvLibraryHashIndex index = indexAt(repoRoot);

        index.append(List.of(new IndexEntry("HASH2", Path.of("D:\\lib\\two.jpg"))));

        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertThat(lines).containsExactly(
                "\"sha256\",\"path\"",
                "\"HASH1\",\"D:\\lib\\one.jpg\"",
                "\"HASH2\",\"D:\\lib\\two.jpg\"");
    }

    @Test
    void appendInsertsMissingNewlineBeforeNewRowsWhenLastLineWasNotTerminated(@TempDir Path repoRoot)
            throws IOException {
        Path csv = repoRoot.resolve("logs").resolve("library-hashes.csv");
        Files.createDirectories(csv.getParent());
        // Deliberately no trailing newline after the last row - see the WHY in append()'s
        // needsLeadingNewline comment.
        Files.writeString(csv, """
                "sha256","path"
                "HASH1","D:\\lib\\one.jpg\"""", StandardCharsets.UTF_8);
        CsvLibraryHashIndex index = indexAt(repoRoot);

        index.append(List.of(new IndexEntry("HASH2", Path.of("D:\\lib\\two.jpg"))));

        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertThat(lines).containsExactly(
                "\"sha256\",\"path\"",
                "\"HASH1\",\"D:\\lib\\one.jpg\"",
                "\"HASH2\",\"D:\\lib\\two.jpg\"");
        assertThat(index.load()).containsOnly(
                Map.entry("HASH1", List.of(Path.of("D:\\lib\\one.jpg"))),
                Map.entry("HASH2", List.of(Path.of("D:\\lib\\two.jpg"))));
    }

    @Test
    void roundTripsCommaInPath(@TempDir Path repoRoot) {
        CsvLibraryHashIndex index = indexAt(repoRoot);
        Path trickyPath = Path.of("D:\\lib\\a, b.jpg");

        index.append(List.of(new IndexEntry("HASH3", trickyPath)));

        assertThat(index.load()).containsEntry("HASH3", List.of(trickyPath));
    }

    private static CsvLibraryHashIndex indexAt(Path repoRoot) {
        return new CsvLibraryHashIndex(repoRoot.resolve("logs").resolve("library-hashes.csv"));
    }
}
