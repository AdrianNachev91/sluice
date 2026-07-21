package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.imaging.MontageBuilder.MontageTile;
import photos.sluice.domain.cull.MontageConfig;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MontageBuilderTest {

    private static final int TILE_SIZE = 224;
    // Mirrors MontageBuilder's own private CELL_PADDING - can't reference it directly, so a change
    // to one without the other would silently skew every pixel-math assertion below.
    private static final int CELL_PADDING = 3;
    private static final Color BACKGROUND = new Color(0x11, 0x11, 0x11);

    private final MontageBuilder builder = new MontageBuilder();

    @Test
    void throwsForAnEmptyTileList() {
        assertThatThrownBy(() -> builder.compose(List.of(), MontageConfig.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void composesASingleTileMontageWithCanvasSizedForOneCellPlusPadding() {
        var config = new MontageConfig(TILE_SIZE, 1);
        var tile = new MontageTile(solidImage(TILE_SIZE, TILE_SIZE, Color.RED), "a.jpg");

        BufferedImage montage = builder.compose(List.of(tile), config);

        assertThat(montage.getWidth()).isEqualTo(TILE_SIZE + 2 * CELL_PADDING);
        assertThat(montage.getHeight())
                .isEqualTo(TILE_SIZE + MontageBuilder.labelBandHeight() + 2 * CELL_PADDING);
    }

    @Test
    void composesAFullGridWithCanvasSizedForTheExactRowAndColumnCount() {
        var config = new MontageConfig(TILE_SIZE, 3);
        List<MontageTile> tiles = solidTiles(6);

        BufferedImage montage = builder.compose(tiles, config);

        int cellWidth = TILE_SIZE + 2 * CELL_PADDING;
        int cellHeight = TILE_SIZE + MontageBuilder.labelBandHeight() + 2 * CELL_PADDING;
        assertThat(montage.getWidth()).isEqualTo(3 * cellWidth);
        assertThat(montage.getHeight()).isEqualTo(2 * cellHeight);
    }

    @Test
    void aPartialLastRowLeavesUnusedCellsAsPlainBackgroundWithoutShrinkingCanvasWidth() {
        var config = new MontageConfig(TILE_SIZE, 3);
        List<MontageTile> tiles = solidTiles(4);

        BufferedImage montage = builder.compose(tiles, config);

        int cellWidth = TILE_SIZE + 2 * CELL_PADDING;
        int cellHeight = TILE_SIZE + MontageBuilder.labelBandHeight() + 2 * CELL_PADDING;
        // Row 2 (index 1) has only the first of its 3 columns filled - canvas stays full 3-wide.
        assertThat(montage.getWidth()).isEqualTo(3 * cellWidth);

        int secondRowMidY = cellHeight + cellHeight / 2;
        int col1CenterX = cellWidth + cellWidth / 2;
        int col2CenterX = 2 * cellWidth + cellWidth / 2;
        assertThat(new Color(montage.getRGB(col1CenterX, secondRowMidY))).isEqualTo(BACKGROUND);
        assertThat(new Color(montage.getRGB(col2CenterX, secondRowMidY))).isEqualTo(BACKGROUND);
    }

    @Test
    void aLandscapeAspectTileIsCenteredWithinItsSquareImageSlot() {
        var config = new MontageConfig(TILE_SIZE, 1);
        var tile = new MontageTile(solidImage(TILE_SIZE, TILE_SIZE / 2, Color.RED), "a.jpg");

        BufferedImage montage = builder.compose(List.of(tile), config);

        int centerX = CELL_PADDING + TILE_SIZE / 2;
        assertThat(new Color(montage.getRGB(centerX, CELL_PADDING + TILE_SIZE / 2))).isEqualTo(Color.RED);
        // Inside the tileSize x tileSize slot, above/below the shorter image's real bounds.
        assertThat(new Color(montage.getRGB(centerX, CELL_PADDING + TILE_SIZE / 8))).isEqualTo(BACKGROUND);
        assertThat(new Color(montage.getRGB(centerX, CELL_PADDING + TILE_SIZE - TILE_SIZE / 8)))
                .isEqualTo(BACKGROUND);
    }

    @Test
    void aPortraitAspectTileIsCenteredWithinItsSquareImageSlot() {
        var config = new MontageConfig(TILE_SIZE, 1);
        var tile = new MontageTile(solidImage(TILE_SIZE / 2, TILE_SIZE, Color.RED), "a.jpg");

        BufferedImage montage = builder.compose(List.of(tile), config);

        int centerY = CELL_PADDING + TILE_SIZE / 2;
        assertThat(new Color(montage.getRGB(CELL_PADDING + TILE_SIZE / 2, centerY))).isEqualTo(Color.RED);
        // Inside the tileSize x tileSize slot, left/right of the narrower image's real bounds.
        assertThat(new Color(montage.getRGB(CELL_PADDING + TILE_SIZE / 8, centerY))).isEqualTo(BACKGROUND);
        assertThat(new Color(montage.getRGB(CELL_PADDING + TILE_SIZE - TILE_SIZE / 8, centerY)))
                .isEqualTo(BACKGROUND);
    }

    @Test
    void theLabelBandContainsNonBackgroundPixelsBelowTheImageWithoutDisturbingTheImageArea() {
        var config = new MontageConfig(TILE_SIZE, 1);
        var tile = new MontageTile(solidImage(TILE_SIZE, TILE_SIZE, Color.RED), "a.jpg");

        BufferedImage montage = builder.compose(List.of(tile), config);

        int imageCenter = CELL_PADDING + TILE_SIZE / 2;
        assertThat(new Color(montage.getRGB(imageCenter, imageCenter))).isEqualTo(Color.RED);

        // A pixel differing from the flat background proves real glyph pixels were drawn. Not
        // "equals white" - anti-aliased glyph edges render slightly differently across the
        // project's Ubuntu/Windows CI matrix.
        int bandTop = CELL_PADDING + TILE_SIZE;
        int bandBottom = bandTop + MontageBuilder.labelBandHeight();
        int width = montage.getWidth();
        boolean foundNonBackground = IntStream.range(bandTop, bandBottom)
                .anyMatch(py -> IntStream.range(0, width)
                        .anyMatch(px -> montage.getRGB(px, py) != BACKGROUND.getRGB()));
        assertThat(foundNonBackground).isTrue();
    }

    @Test
    void aFilenameLongerThanTheCellWidthIsClippedRatherThanBleedingIntoTheNeighboringCell() {
        var config = new MontageConfig(TILE_SIZE, 2);
        String longLabel = "a".repeat(200) + ".jpg";
        var firstTile = new MontageTile(solidImage(TILE_SIZE, TILE_SIZE, Color.RED), longLabel);
        var secondTile = new MontageTile(solidImage(TILE_SIZE, TILE_SIZE, Color.BLUE), "b.jpg");

        BufferedImage montage = builder.compose(List.of(firstTile, secondTile), config);

        int cellWidth = TILE_SIZE + 2 * CELL_PADDING;
        // The second tile's own image area is unaffected by the first cell's overflowing label.
        int secondImageCenterX = cellWidth + CELL_PADDING + TILE_SIZE / 2;
        assertThat(new Color(montage.getRGB(secondImageCenterX, CELL_PADDING + TILE_SIZE / 2)))
                .isEqualTo(Color.BLUE);

        // Just past the first cell's right edge, near its own left padding. "b.jpg"'s short,
        // centered label doesn't reach this far left within its own cell. Any non-background
        // pixel here would mean the first cell's long label bled across the boundary.
        int justPastBoundary = cellWidth + 2;
        int labelMidY = CELL_PADDING + TILE_SIZE + MontageBuilder.labelBandHeight() / 2;
        assertThat(new Color(montage.getRGB(justPastBoundary, labelMidY))).isEqualTo(BACKGROUND);
    }

    private static List<MontageTile> solidTiles(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new MontageTile(solidImage(TILE_SIZE, TILE_SIZE, Color.RED), "tile-" + i + ".jpg"))
                .toList();
    }

    private static BufferedImage solidImage(int width, int height, Color color) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }
}
