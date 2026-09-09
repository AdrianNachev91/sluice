package photos.sluice.domain.paths;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SortFolderNamesTest {

    @Test
    void aDatedFolderIsOneASortWrites() {
        assertThat(SortFolderNames.isSortFolderName("2019-06")).isTrue();
        assertThat(SortFolderNames.isSortFolderName("1999-12")).isTrue();
    }

    @Test
    void theUndatedFolderIsOneASortWritesWhateverItsCase() {
        assertThat(SortFolderNames.isSortFolderName("Unsorted")).isTrue();
        assertThat(SortFolderNames.isSortFolderName("unsorted")).isTrue();
        assertThat(SortFolderNames.isSortFolderName("UNSORTED")).isTrue();
    }

    @Test
    void aCategoryNameIsNotOneASortWrites() {
        assertThat(SortFolderNames.isSortFolderName("food")).isFalse();
        assertThat(SortFolderNames.isSortFolderName("junk")).isFalse();
    }

    // Each of these differs from a name a sort writes in one way only, so a pattern loose at either
    // end would take it.
    @Test
    void aNameThatOnlyResemblesOneIsNotOneASortWrites() {
        assertThat(SortFolderNames.isSortFolderName("2019-6")).isFalse();
        assertThat(SortFolderNames.isSortFolderName("2019-06-01")).isFalse();
        assertThat(SortFolderNames.isSortFolderName("x2019-06")).isFalse();
        assertThat(SortFolderNames.isSortFolderName("2019-06_beach")).isFalse();
        assertThat(SortFolderNames.isSortFolderName("unsorted-later")).isFalse();
    }
}
