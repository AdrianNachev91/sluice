package photos.sluice.domain.imaging;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.model.Dimensions;
import photos.sluice.domain.model.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class LowResGateTest {

    @Test
    void smallFileIsLowResRegardlessOfDimensions() {
        boolean result = LowResGate.isLowRes(10_000, null, MediaType.PHOTO, "jpg");

        assertThat(result).isTrue();
    }

    @Test
    void tinyPixelDimensionsAreLowResEvenWithLargeFile() {
        boolean result = LowResGate.isLowRes(
                200_000, new Dimensions(500, 300), MediaType.PHOTO, "jpg");

        assertThat(result).isTrue();
    }

    @Test
    void largeFileWithAmpleDimensionsIsNotLowRes() {
        boolean result = LowResGate.isLowRes(
                200_000, new Dimensions(3000, 2000), MediaType.PHOTO, "jpg");

        assertThat(result).isFalse();
    }

    @Test
    void missingDimensionsWithLargeFileDefaultsToNotLowRes() {
        boolean result = LowResGate.isLowRes(200_000, null, MediaType.PHOTO, "jpg");

        assertThat(result).isFalse();
    }

    @Test
    void videoIsExemptEvenWhenTiny() {
        boolean result = LowResGate.isLowRes(1_000, null, MediaType.VIDEO, "mp4");

        assertThat(result).isFalse();
    }

    @Test
    void svgIsExemptEvenWhenTiny() {
        boolean result = LowResGate.isLowRes(1_000, null, MediaType.PHOTO, "svg");

        assertThat(result).isFalse();
    }

    @Test
    void fileSizeThresholdIsStrictlyLessThan() {
        boolean result = LowResGate.isLowRes(
                51_200, new Dimensions(3000, 2000), MediaType.PHOTO, "jpg");

        assertThat(result).isFalse();
    }

    @Test
    void pixelThresholdIsStrictlyLessThan() {
        boolean result = LowResGate.isLowRes(
                200_000, new Dimensions(640, 480), MediaType.PHOTO, "jpg");

        assertThat(result).isFalse();
    }
}
