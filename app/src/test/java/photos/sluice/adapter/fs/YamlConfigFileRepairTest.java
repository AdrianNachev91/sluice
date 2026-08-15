package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.MalformedSettingsException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlConfigFileRepairTest {

    @Test
    void removesTheSettingThatStoppedStartup(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                sluice:
                  montage:
                    tile-size: many
                    tiles-per-row: 5
                """);

        final boolean removed = new YamlConfigFileRepair(file).removeSetting("sluice.montage.tile-size");

        assertThat(removed).isTrue();
        assertThat(Files.readString(file)).doesNotContain("tile-size").contains("tiles-per-row: 5");
    }

    @Test
    void keepsEverySettingItWasNotAskedAbout(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                sluice:
                  paths:
                    repo-root: /photos/work
                  montage:
                    tile-size: many
                  cull:
                    categories:
                      - name: junk
                        description: objectively worthless shots
                unrelated:
                  kept: true
                """);

        new YamlConfigFileRepair(file).removeSetting("sluice.montage.tile-size");

        assertThat(Files.readString(file))
                .contains("repo-root: /photos/work")
                .contains("name: junk")
                .contains("description: objectively worthless shots")
                .contains("kept: true");
    }

    // Config binding reads all three spellings as one key, so a repair leaving one behind would let
    // the value the app just refused come straight back.
    @Test
    void removesTheSettingWhateverSpellingItWasWrittenIn(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                sluice:
                  montage:
                    tileSize: many
                    tile_size: also-many
                """);

        final boolean removed = new YamlConfigFileRepair(file).removeSetting("sluice.montage.tile-size");

        assertThat(removed).isTrue();
        assertThat(Files.readString(file)).doesNotContain("many");
    }

    @Test
    void findsTheSettingUnderAGroupWrittenInAnotherSpelling(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                sluice:
                  cull:
                    providerSettings:
                      max-retries: lots
                """);

        final boolean removed = new YamlConfigFileRepair(file)
                .removeSetting("sluice.cull.provider-settings.max-retries");

        assertThat(removed).isTrue();
        assertThat(Files.readString(file)).doesNotContain("lots");
    }

    // The comment in the fixture is the instrument. A rewrite would reproduce every setting and
    // drop that line, so it is what tells an untouched file from an identically rewritten one.
    @Test
    void leavesTheFileAloneWhenTheSettingIsNotInIt(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        final String original = """
                # the folders Sluice works in
                sluice:
                  paths:
                    repo-root: /photos/work
                """;
        Files.writeString(file, original);

        final boolean removed = new YamlConfigFileRepair(file).removeSetting("sluice.montage.tile-size");

        assertThat(removed).isFalse();
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    @Test
    void leavesTheFileAloneWhenTheSettingIsNamedWithAnIndex(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        final String original = """
                sluice:
                  cull:
                    categories:
                      - name: junk
                """;
        Files.writeString(file, original);

        final boolean removed = new YamlConfigFileRepair(file)
                .removeSetting("sluice.cull.categories[0].description");

        assertThat(removed).isFalse();
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    @Test
    void aFailedRemovalLeavesNeitherALeftoverNorAChangedFile(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        final String original = "sluice:\n  montage:\n    tile-size: many\n";
        Files.writeString(file, original);

        assertThatThrownBy(() -> new YamlConfigFileRepair(new FailingWriteDocument(file), Clock.systemUTC())
                .removeSetting("sluice.montage.tile-size"))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(Files.readString(file)).isEqualTo(original);
        try (final var entries = Files.list(dir)) {
            assertThat(entries).containsExactly(file);
        }
    }

    @Test
    void refusesToRemoveASettingFromAFileItCannotParse(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice:\n  montage:\n    tile-size: [1, 2\n");

        assertThatThrownBy(() -> new YamlConfigFileRepair(file).removeSetting("sluice.montage.tile-size"))
                .isInstanceOf(MalformedSettingsException.class);
    }

    // The file parses, so the refusal cannot come from the parser. Overwriting what the user wrote
    // as something else is what the read refuses, and a repair inherits that.
    @Test
    void refusesToRemoveASettingFromUnderAnEntryThatIsNotAGroup(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        final String original = "sluice: off\n";
        Files.writeString(file, original);

        assertThatThrownBy(() -> new YamlConfigFileRepair(file).removeSetting("sluice.montage.tile-size"))
                .isInstanceOf(MalformedSettingsException.class);

        assertThat(Files.readString(file)).isEqualTo(original);
    }

    // The moment is the only thing telling a folder of these apart. Picking the right one to retype
    // from is what somebody is there to do.
    @Test
    void movesTheFileAsideUnderTheMomentItWasSetAside(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice:\n  montage:\n    tile-size: [1, 2\n");

        final Path moved = repair(file, "2026-08-15T21:40:12Z").setAside();

        assertThat(file).doesNotExist();
        assertThat(moved).isEqualTo(dir.resolve("config.broken-2026-08-15_21-40-12.yml"))
                .hasContent("sluice:\n  montage:\n    tile-size: [1, 2\n");
    }

    @Test
    void keepsEverySetAsideFileRatherThanTheLatest(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "first\n");
        repair(file, "2026-08-15T21:40:12Z").setAside();
        Files.writeString(file, "second\n");

        final Path moved = repair(file, "2026-08-16T09:03:00Z").setAside();

        assertThat(dir.resolve("config.broken-2026-08-15_21-40-12.yml")).hasContent("first\n");
        assertThat(moved).isEqualTo(dir.resolve("config.broken-2026-08-16_09-03-00.yml"))
                .hasContent("second\n");
    }

    // Two repairs inside one second, which is the only way one moment needs a second name.
    @Test
    void numbersASecondFileSetAsideInTheSameSecond(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "first\n");
        repair(file, "2026-08-15T21:40:12Z").setAside();
        Files.writeString(file, "second\n");

        final Path moved = repair(file, "2026-08-15T21:40:12Z").setAside();

        assertThat(dir.resolve("config.broken-2026-08-15_21-40-12.yml")).hasContent("first\n");
        assertThat(moved).isEqualTo(dir.resolve("config.broken-2026-08-15_21-40-12-2.yml"))
                .hasContent("second\n");
    }

    // Nothing removes the kept files, so the names for one moment can all be taken, and the user is
    // the only one who can free any. So the refusal names the folder and what to delete in it,
    // rather than reporting that a move failed.
    @Test
    void aMomentWithNoFreeNameLeftRefusesInWordsTheUserCanActOn(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice:\n");
        Files.writeString(dir.resolve("config.broken-2026-08-15_21-40-12.yml"), "");
        for (int taken = 2; taken <= 100; taken++) {
            Files.writeString(dir.resolve("config.broken-2026-08-15_21-40-12-" + taken + ".yml"), "");
        }

        assertThatThrownBy(() -> repair(file, "2026-08-15T21:40:12Z").setAside())
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(dir.toString())
                .hasMessageContaining(".broken")
                .hasMessageContaining("Delete");

        assertThat(file).exists();
    }

    @Test
    void refusesToSetAsideAFileThatIsNotThere(@TempDir final Path dir) {
        assertThatThrownBy(() -> new YamlConfigFileRepair(dir.resolve("config.yml")).setAside())
                .isInstanceOf(UncheckedIOException.class);
    }

    // A repair whose clock stands still, so a set-aside name is a value the test can name rather
    // than a moment it has to race.
    private static YamlConfigFileRepair repair(final Path configFile, final String moment) {
        return new YamlConfigFileRepair(new YamlConfigFile(configFile),
                Clock.fixed(Instant.parse(moment), ZoneOffset.UTC));
    }
}
