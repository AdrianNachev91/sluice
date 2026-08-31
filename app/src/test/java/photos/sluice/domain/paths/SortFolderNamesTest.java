package photos.sluice.domain.paths;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SortFolderNamesTest {

    @Test
    void aDatedFolderIsOneASortWrites() {
        assertThat(SortFolderNames.writtenByASort("2019-06")).isTrue();
        assertThat(SortFolderNames.writtenByASort("1999-12")).isTrue();
    }

    @Test
    void theUndatedFolderIsOneASortWritesWhateverItsCase() {
        assertThat(SortFolderNames.writtenByASort("Unsorted")).isTrue();
        assertThat(SortFolderNames.writtenByASort("unsorted")).isTrue();
        assertThat(SortFolderNames.writtenByASort("UNSORTED")).isTrue();
    }

    @Test
    void aCategoryNameIsNotOneASortWrites() {
        assertThat(SortFolderNames.writtenByASort("food")).isFalse();
        assertThat(SortFolderNames.writtenByASort("junk")).isFalse();
    }

    // Each of these differs from a name a sort writes in one way only, so a pattern loose at either
    // end would take it.
    @Test
    void aNameThatOnlyResemblesOneIsNotOneASortWrites() {
        assertThat(SortFolderNames.writtenByASort("2019-6")).isFalse();
        assertThat(SortFolderNames.writtenByASort("2019-06-01")).isFalse();
        assertThat(SortFolderNames.writtenByASort("x2019-06")).isFalse();
        assertThat(SortFolderNames.writtenByASort("2019-06_beach")).isFalse();
        assertThat(SortFolderNames.writtenByASort("unsorted-later")).isFalse();
    }
}
