package photos.sluice.adapter.imaging;

import org.springframework.stereotype.Component;
import photos.sluice.domain.cull.MontageConfig;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

@Component
public class MontageBuilder {

    // Distinct from TileRenderer.placeholder()'s #444444 - this is the grid's own canvas color,
    // not a stand-in-for-missing-content signal.
    private static final Color BACKGROUND = new Color(0x11, 0x11, 0x11);
    private static final Color LABEL_COLOR = Color.WHITE;
    private static final int CELL_PADDING = 3;

    // A fixed absolute size, not scaled by tileSize like placeholder()'s tileSize/10f. That ratio
    // fills a whole tile with bold stand-in text. This is a small caption below a real photo. It
    // should stay a consistent, legible size regardless of how big tileSize is configured.
    private static final float LABEL_FONT_SIZE = 9f;
    private static final int LABEL_VERTICAL_MARGIN = 2;

    // A rendering artifact (holds a BufferedImage), not a domain concept - mirrors TileRenderer's
    // own TileResult being nested in adapter/imaging rather than domain/cull.
    public record MontageTile(BufferedImage image, String label) {
    }

    public BufferedImage compose(List<MontageTile> tiles, MontageConfig config) {
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("cannot compose a montage from an empty tile list");
        }
        int tileSize = config.tileSize();
        int tilesPerRow = config.tilesPerRow();
        int labelHeight = labelBandHeight();
        int cellWidth = tileSize + 2 * CELL_PADDING;
        int cellHeight = tileSize + labelHeight + 2 * CELL_PADDING;
        int rows = ceilDiv(tiles.size(), tilesPerRow);
        int canvasWidth = tilesPerRow * cellWidth;
        int canvasHeight = rows * cellHeight;

        var canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, canvasWidth, canvasHeight);
            // The canvas is pre-filled with BACKGROUND above, so a partial last row's unused cells
            // need no special-casing - they stay background simply because nothing draws there.
            for (int i = 0; i < tiles.size(); i++) {
                int row = i / tilesPerRow;
                int col = i % tilesPerRow;
                drawCell(g, tiles.get(i), col * cellWidth, row * cellHeight, cellWidth, cellHeight, tileSize);
            }
        } finally {
            g.dispose();
        }
        return canvas;
    }

    // Graphics2D.create(x, y, width, height) translates the origin to the cell's position and
    // clips to its size, in one call. All coordinate math below is cell-local (0,0-origin). An
    // overlong label is clipped at the cell's own edge automatically, with no width-measuring or
    // ellipsis logic needed.
    private static void drawCell(
            Graphics2D parent, MontageTile tile, int x, int y, int width, int height, int tileSize) {
        var cell = (Graphics2D) parent.create(x, y, width, height);
        try {
            cell.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            BufferedImage image = tile.image();
            // TileRenderer guarantees image.getWidth()/getHeight() <= tileSize, so these are always
            // >= CELL_PADDING - the image itself never needs clipping, only the label can overflow.
            int imgX = CELL_PADDING + (tileSize - image.getWidth()) / 2;
            int imgY = CELL_PADDING + (tileSize - image.getHeight()) / 2;
            cell.drawImage(image, imgX, imgY, null);

            cell.setColor(LABEL_COLOR);
            cell.setFont(cell.getFont().deriveFont(Font.PLAIN, LABEL_FONT_SIZE));
            FontMetrics metrics = cell.getFontMetrics();
            String label = tile.label();
            int textWidth = metrics.stringWidth(label);
            int textX = CELL_PADDING + (tileSize - textWidth) / 2;
            int textY = CELL_PADDING + tileSize + LABEL_VERTICAL_MARGIN + metrics.getAscent();
            cell.drawString(label, textX, textY);
        } finally {
            cell.dispose();
        }
    }

    // Font metrics need a Graphics context to measure, but the canvas height must be known before
    // one exists. A throwaway 1x1 probe image breaks that chicken/egg problem. Package-private so
    // the test class can compute the same expected height rather than hardcoding a JDK/OS-dependent
    // pixel value (this project's CI runs both Ubuntu and Windows).
    static int labelBandHeight() {
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        try {
            g.setFont(g.getFont().deriveFont(Font.PLAIN, LABEL_FONT_SIZE));
            return g.getFontMetrics().getHeight() + 2 * LABEL_VERTICAL_MARGIN;
        } finally {
            g.dispose();
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
