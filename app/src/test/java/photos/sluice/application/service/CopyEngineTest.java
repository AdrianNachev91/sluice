package photos.sluice.application.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.domain.copy.CopySummary;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopyEngineTest {

    @Nested
    class Copying {

        @Test
        void writesEveryFileWhereItSatUnderTheSourceRoot(@TempDir final Path from, @TempDir final Path to) {
            write(from.resolve("loose.jpg"), "loose");
            write(from.resolve("2019/06/holiday.jpg"), "holiday");
            write(from.resolve("2019/07/beach.jpg"), "beach");

            final CopySummary summary = copyEngine().copyTree(from, to, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER);

            assertThat(to.resolve("loose.jpg")).hasContent("loose");
            assertThat(to.resolve("2019/06/holiday.jpg")).hasContent("holiday");
            assertThat(to.resolve("2019/07/beach.jpg")).hasContent("beach");
            assertThat(summary).isEqualTo(new CopySummary(3, 3, false));
        }

        @Test
        void doesNotCarryATransferThatNeverLandedIntoTheNewLibrary(@TempDir final Path from,
                                                                   @TempDir final Path to) {
            write(from.resolve("2019/06/holiday.jpg"), "holiday");
            write(from.resolve("2019/06/beach.jpg.sluice-part"), "half a beach");

            final CopySummary summary = copyEngine().copyTree(from, to, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER);

            assertThat(to.resolve("2019/06/beach.jpg.sluice-part")).doesNotExist();
            assertThat(summary).isEqualTo(new CopySummary(1, 1, false));
        }

        @Test
        void leavesTheSourceWhereItIs(@TempDir final Path from, @TempDir final Path to) {
            write(from.resolve("2019/06/holiday.jpg"), "holiday");

            copyEngine().copyTree(from, to, ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(from.resolve("2019/06/holiday.jpg")).hasContent("holiday");
        }

        @Test
        void writesBesideAFileTheDestinationAlreadyHoldsRatherThanOverIt(@TempDir final Path from,
                                                                        @TempDir final Path to) {
            write(from.resolve("holiday.jpg"), "the one being copied");
            write(to.resolve("holiday.jpg"), "the one already there");

            copyEngine().copyTree(from, to, ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(to.resolve("holiday.jpg")).hasContent("the one already there");
            assertThat(to.resolve("holiday (2).jpg")).hasContent("the one being copied");
        }

        @Test
        void skipsAFileTheDestinationAlreadyHoldsUnderTheSameNameAndSize(@TempDir final Path from,
                                                                        @TempDir final Path to) {
            write(from.resolve("2019/holiday.jpg"), "holiday");
            write(from.resolve("beach.jpg"), "beach");
            write(to.resolve("2019/holiday.jpg"), "holiday");

            final CopySummary summary = copyEngine().copyTree(from, to, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER);

            assertThat(filesUnder(to)).containsExactlyInAnyOrder(
                    to.resolve("2019/holiday.jpg"), to.resolve("beach.jpg"));
            assertThat(summary).isEqualTo(new CopySummary(1, 2, false));
        }

        @Test
        void writesBesideAFileOfTheSameNameThatDiffersInSize(@TempDir final Path from, @TempDir final Path to) {
            write(from.resolve("holiday.jpg"), "the one being copied");
            write(to.resolve("holiday.jpg"), "different");

            copyEngine().copyTree(from, to, ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(to.resolve("holiday.jpg")).hasContent("different");
            assertThat(to.resolve("holiday (2).jpg")).hasContent("the one being copied");
        }

        @Test
        void reportsNothingCopiedForAnEmptySource(@TempDir final Path from, @TempDir final Path to) {
            assertThat(copyEngine().copyTree(from, to, ProgressCallback.NO_OP, CancellationSignal.NEVER))
                    .isEqualTo(new CopySummary(0, 0, false));
        }

        @Test
        void ticksOncePerFileAgainstTheWholeTotal(@TempDir final Path from, @TempDir final Path to) {
            write(from.resolve("one.jpg"), "one");
            write(from.resolve("two.jpg"), "two");
            final var ticks = new ArrayList<String>();

            copyEngine().copyTree(from, to, (current, total) -> ticks.add(current + "/" + total),
                    CancellationSignal.NEVER);

            assertThat(ticks).containsExactly("1/2", "2/2");
        }
    }

    @Nested
    class Cancelling {

        @Test
        void stopsBeforeTheNextFileAndSaysHowFarItGot(@TempDir final Path from, @TempDir final Path to) {
            write(from.resolve("one.jpg"), "one");
            write(from.resolve("two.jpg"), "two");
            write(from.resolve("three.jpg"), "three");
            final CancellationSignal afterOneFile = cancelAfter(1);

            final CopySummary summary =
                    copyEngine().copyTree(from, to, ProgressCallback.NO_OP, afterOneFile);

            assertThat(summary).isEqualTo(new CopySummary(1, 3, true));
            assertThat(filesUnder(to)).hasSize(1);
        }

        // A signal already true when the copy starts must reach the check before the first write,
        // not after it.
        @Test
        void copiesNothingWhenItIsAlreadyCancelled(@TempDir final Path from, @TempDir final Path to) {
            write(from.resolve("one.jpg"), "one");

            final CopySummary summary =
                    copyEngine().copyTree(from, to, ProgressCallback.NO_OP, () -> true);

            assertThat(summary).isEqualTo(new CopySummary(0, 1, true));
            assertThat(filesUnder(to)).isEmpty();
        }
    }

    @Nested
    class OverlappingTrees {

        @Test
        void refusesADestinationInsideTheSource(@TempDir final Path from) {
            write(from.resolve("holiday.jpg"), "holiday");
            final Path inside = from.resolve("copy-of-itself");

            assertThatThrownBy(() -> copyEngine().copyTree(from, inside, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(from.toString());
            assertThat(inside).doesNotExist();
        }

        @Test
        void refusesASourceInsideTheDestination(@TempDir final Path to) {
            final Path inside = to.resolve("library");
            write(inside.resolve("holiday.jpg"), "holiday");

            assertThatThrownBy(() -> copyEngine().copyTree(inside, to, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(filesUnder(to)).containsExactly(inside.resolve("holiday.jpg"));
        }

        @Test
        void refusesOneDirectoryCopiedIntoItself(@TempDir final Path root) {
            write(root.resolve("holiday.jpg"), "holiday");

            assertThatThrownBy(() -> copyEngine().copyTree(root, root.resolve("."), ProgressCallback.NO_OP,
                    CancellationSignal.NEVER))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(filesUnder(root)).containsExactly(root.resolve("holiday.jpg"));
        }
    }

    private static CopyEngine copyEngine() {
        return new CopyEngine(new NioMediaStore());
    }

    // Answers false for the first count calls and true after that. A test then names the file the
    // copy stops before, rather than a duration it waits.
    private static CancellationSignal cancelAfter(final int count) {
        final int[] asked = {0};
        return () -> asked[0]++ >= count;
    }

    private static List<Path> filesUnder(final Path root) {
        return new NioMediaStore().listFiles(root);
    }

    private static void write(final Path file, final String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
