package photos.sluice.domain.commit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryBucketTest {

    @Test
    void classifiesTheThreeKnownFirstSegments() {
        assertThat(LibraryBucket.ofFirstSegment("Photos")).isEqualTo(LibraryBucket.PHOTOS);
        assertThat(LibraryBucket.ofFirstSegment("Videos")).isEqualTo(LibraryBucket.VIDEOS);
        assertThat(LibraryBucket.ofFirstSegment("Funny")).isEqualTo(LibraryBucket.FUNNY);
    }

    @Test
    void fallsBackToOtherForAnUnrecognizedFirstSegment() {
        assertThat(LibraryBucket.ofFirstSegment("Unreviewable")).isEqualTo(LibraryBucket.OTHER);
    }
}
