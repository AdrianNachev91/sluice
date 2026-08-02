package photos.sluice.domain.scan;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Checks the orphan sweep against every Takeout sidecar naming shape a real export was observed to
// contain. The shapes and their counts live in takeout-sidecar-shapes.md, which is the only
// surviving record of that survey.
//
// The unit tests beside this one pick their fixtures to isolate a single rule. These do the
// opposite. They are whatever Google actually emitted, so a rule change that looks harmless against
// a hand-picked case still has to face the real distribution.
//
// The fixture is a Markdown table so it stays readable as a document. Only its table rows are
// parsed, and everything around them is prose for a human.
class TakeoutSidecarShapesTest {

    private static final String FIXTURE = "/takeout-sidecar-shapes.md";

    private final SidecarSweep sweep = new SidecarSweep();
    private final TakeoutSidecarPairer pairer = new TakeoutSidecarPairer();

    /**
     * One observed naming shape, and what the sweep owes it.
     *
     * @param shape {@link String} short label for the shape, used as the test's display name
     * @param sidecar {@link String} the JSON file name
     * @param media a {@link List} of {@link String} media file names in the same directory
     * @param expectSwept boolean true if the sweep should return this sidecar as orphaned
     * @param seen int how many files of this shape the surveyed export held
     * @param why {@link String} the rule that decides the verdict
     */
    record Shape(String shape, String sidecar, List<String> media, boolean expectSwept, int seen, String why) {
        @Override
        public String toString() {
            return this.shape + " (" + this.seen + " seen; " + this.why + ")";
        }
    }

    @ParameterizedTest
    @MethodSource("observedShapes")
    void theSweepHandlesEveryObservedNamingShape(final Shape shape) {
        final Path json = Path.of("Inbox", shape.sidecar());
        final List<Path> media = shape.media().stream().map(name -> Path.of("Inbox", name)).toList();
        final Map<Path, Path> pairing = this.pairer.pair(media, List.of(json)).sidecarsByMedia();

        final List<Path> orphaned = this.sweep.findOrphaned(media, List.of(json), pairing);

        assertThat(orphaned.contains(json))
                .as("%s: sidecar %s with media %s", shape.shape(), shape.sidecar(), shape.media())
                .isEqualTo(shape.expectSwept());
    }

    /**
     * Reads the shape table out of the fixture. Only its table rows are data, and everything
     * around them is prose.
     *
     * @return a {@link List} of {@link Shape} every observed shape the fixture describes
     */
    static List<Shape> observedShapes() {
        final List<Shape> shapes = new ArrayList<>();
        try (final InputStream in = TakeoutSidecarShapesTest.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + FIXTURE);
            }
            for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                final String row = line.strip();
                if (row.startsWith("|") && !isHeaderRow(row)) {
                    shapes.add(parse(row));
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        // A silently empty fixture would turn this whole class into a no-op that still reports green.
        assertThat(shapes).as("shapes parsed from %s", FIXTURE).isNotEmpty();
        return shapes;
    }

    /**
     * Skips the table's own two header lines: the column titles, and the dashes under them.
     *
     * @param row {@link String} one stripped line beginning with a pipe
     * @return boolean true if the line is table furniture rather than a shape
     */
    private static boolean isHeaderRow(final String row) {
        return row.startsWith("| Shape") || row.chars().allMatch(c -> c == '|' || c == '-' || c == ' ');
    }

    /**
     * Turns one table row into a shape. A malformed row throws rather than being skipped, so a
     * broken fixture fails loudly instead of quietly testing less.
     *
     * @param row {@link String} one stripped table row, pipes included
     * @return {@link Shape} the shape that row describes
     */
    private static Shape parse(final String row) {
        // A leading pipe makes split produce an empty first element, so the columns start at 1.
        final String[] columns = Arrays.stream(row.split("\\|")).map(String::strip).toArray(String[]::new);
        final List<String> media = columns[3].equals("-") ? List.of()
                : Arrays.stream(columns[3].split(";")).map(String::strip).toList();
        return new Shape(columns[1], columns[2], media, expectSwept(columns[1], columns[4]),
                Integer.parseInt(columns[5]), columns[6]);
    }

    /**
     * Rejects anything that is not one of the two verdicts. Reading an unrecognized cell as "keep"
     * would let a typo silently invert a row's expectation while the suite still reported green.
     *
     * @param shape {@link String} the row's shape label, for the failure message
     * @param verdict {@link String} the row's verdict cell
     * @return boolean true if the sweep should return this sidecar as orphaned
     */
    private static boolean expectSwept(final String shape, final String verdict) {
        return switch (verdict) {
            case "SWEPT" -> true;
            case "KEPT" -> false;
            default -> throw new IllegalStateException(
                    "Shape '" + shape + "' has verdict '" + verdict + "', expected KEPT or SWEPT");
        };
    }
}
