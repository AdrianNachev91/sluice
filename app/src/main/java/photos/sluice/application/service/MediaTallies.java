package photos.sluice.application.service;

import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.domain.model.MediaType;
import photos.sluice.domain.model.Numerals;
import photos.sluice.domain.scan.MediaTypeDetector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Counts what is waiting in the Inbox and what is staged in Sorted, without moving or dating
 * anything.
 *
 * <p>Both answers come from walking a tree and classifying each file by its extension. Neither
 * resolves a date. That is the expensive half of a sort, and the reason the Inbox can only ever be
 * described as a total.
 *
 * <p>A photo counts toward a year only where it sits under that year in the Photos tree and
 * classifies as a photo. Those are both conditions a sift applies when it collects candidates, so
 * this is the set a sift would start from. It is not what a sift ends up sending. A photo that
 * cannot render a judgeable tile is dropped before any sheet is built, so this count is the upper
 * bound. Videos are read the same way against the Videos tree.
 */
final class MediaTallies {

    // Where a sort files each kind. Named here as the reader of the two folders SortEngine writes.
    private static final String PHOTOS = "Photos";
    private static final String VIDEOS = "Videos";

    // How wide a month folder's name is, everywhere this app writes one.
    private static final int MONTH_DIGITS = 2;

    private static final int YEAR_DIGITS = 4;

    private static final int FIRST_MONTH = 1;
    private static final int LAST_MONTH = 12;

    private final MediaReader media;
    private final PathsPort paths;
    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();

    /**
     * Creates the tallies over the tree reader and the roots they are taken under.
     *
     * @param media {@link MediaReader} walks a tree and sizes the files in it
     * @param paths {@link PathsPort} resolves the Inbox and Sorted roots
     */
    MediaTallies(final MediaReader media, final PathsPort paths) {
        this.media = media;
        this.paths = paths;
    }

    /**
     * How much is waiting in the Inbox.
     *
     * <p>Sized as well as counted, because a count on its own says nothing about how long a sort
     * will take. Thirty thousand phone photos and thirty thousand raw files are the same number and
     * a very different afternoon.
     *
     * <p>An Inbox folder that is not there answers zero rather than refusing. The roots check has
     * already run by the time this is reached, so a folder missing at this point went missing
     * between the two. Nothing is waiting in a folder that does not exist, which is what the caller
     * asked.
     *
     * @return {@link InboxTally} the count and what it comes to on disk
     */
    InboxTally inbox() {
        final Path inbox = this.paths.inbox();
        if (!this.media.exists(inbox)) {
            return new InboxTally(0, 0);
        }
        final List<Path> waiting = this.media.listFiles(inbox).stream()
                .filter(file -> this.mediaTypeDetector.classify(file).isPresent())
                .toList();
        return new InboxTally(waiting.size(), waiting.stream().mapToLong(this.media::size).sum());
    }

    /**
     * What is staged in Sorted, by year, newest first.
     *
     * <p>Newest first because a year is reached here to sift it or to move it to the library. The
     * year somebody just sorted is the one they came for. The Inbox is the opposite, and is worked
     * oldest first.
     *
     * @return {@link SortedTally} one row per year holding anything
     */
    SortedTally sorted() {
        final Path root = this.paths.sorted();
        final Map<Integer, Counts> byYear = new TreeMap<>(Comparator.reverseOrder());
        this.gather(root.resolve(PHOTOS), MediaType.PHOTO, byYear);
        this.gather(root.resolve(VIDEOS), MediaType.VIDEO, byYear);
        return new SortedTally(byYear.entrySet().stream()
                .map(year -> year.getValue().asRow(year.getKey()))
                .toList());
    }

    /**
     * Adds one tree's files to the running counts, under the year and month each sits in.
     *
     * @param root {@link Path} the Photos or Videos tree
     * @param kind {@link MediaType} what a file there has to be to count
     * @param byYear a {@link Map} of {@link Integer} to {@link Counts} the counts so far, added to
     */
    private void gather(final Path root, final MediaType kind, final Map<Integer, Counts> byYear) {
        if (!this.media.exists(root)) {
            return;
        }
        this.media.listFiles(root).stream()
                .filter(file -> this.mediaTypeDetector.classify(file).filter(kind::equals).isPresent())
                .map(root::relativize)
                .forEach(within -> yearOf(within).ifPresent(year -> {
                    final Counts counts = byYear.computeIfAbsent(year, _ -> new Counts());
                    counts.add(kind);
                    monthOf(within).ifPresent(month -> counts.addToMonth(kind, month));
                }));
    }

    /**
     * Which month of its year a staged file sits in, where it sits in one at all.
     *
     * <p>A file dropped straight into a year folder by hand is in no month, and counts toward the
     * year alone. Nothing this app writes lands there. So that is a folder somebody arranged
     * themselves, rather than a state to fold into month one or month twelve.
     *
     * <p>Padded to two digits, which is how a scope names a month folder when it resolves one. A
     * run narrowed to June reads {@code 06} and nothing else. Counting a hand-made {@code 6} toward
     * June would promise a photo that run could never reach.
     *
     * @param within {@link Path} the file's path below its Photos or Videos root
     * @return {@link OptionalInt} the month, empty where the file is in no month folder
     */
    private static OptionalInt monthOf(final Path within) {
        final OptionalInt month = Folder.MONTH.in(within);
        return month.isPresent() && month.getAsInt() >= FIRST_MONTH && month.getAsInt() <= LAST_MONTH
                ? month
                : OptionalInt.empty();
    }

    /**
     * Which year a staged file sits under, where the folder is one a scope could name.
     *
     * <p>A scope resolves a year folder by its plain digits, so {@code 02019} is a folder no run
     * would ever read. Counted here it would put photos under a year nothing can be scoped to.
     *
     * @param within {@link Path} the file's path below its Photos or Videos root
     * @return {@link OptionalInt} the year, empty where the folder is not one
     */
    private static OptionalInt yearOf(final Path within) {
        return Folder.YEAR.in(within);
    }

    /**
     * The two folders a staged file's own path names, above the file itself.
     *
     * <p>Each knows two things that have to agree: how far below the tree's root it sits, and how a
     * scope spells it. A folder read at one depth and checked against the other's spelling counts
     * photos no run could reach.
     *
     * <p>The depth is stated rather than taken from {@code ordinal()}. Nothing watches the order
     * these are declared in, so two folders both named by plain digits would swap in silence.
     */
    private enum Folder {

        /** Directly under the Photos or Videos tree. */
        YEAR(0, YEAR_DIGITS),

        /** Directly under its year. */
        MONTH(1, MONTH_DIGITS);

        private final int depth;

        private final int digits;

        /**
         * Creates the folder at its own depth below a staged file's root.
         *
         * @param depth int how far below that root this folder sits
         * @param digits int how many digits a scope writes this folder's name in
         */
        Folder(final int depth, final int digits) {
            this.depth = depth;
            this.digits = digits;
        }

        /**
         * The number this folder names, where the name is one a scope would resolve.
         *
         * <p>Reading a folder name as a number throws away how it was written, and how it was
         * written is what decides whether a run would open it. So the number is spelled back out
         * and compared against what is there. A hand-made {@code 6} beside a scope that resolves
         * {@code 06} answers empty, rather than promising a photo that run could never reach.
         *
         * @param within {@link Path} a file's path below its Photos or Videos root
         * @return {@link OptionalInt} the number, empty where this folder is absent, is not a
         *     number, or is not spelled the way a scope would spell it
         */
        private OptionalInt in(final Path within) {
            // The last element is the filename, so a folder at or past it never names one.
            if (within.getNameCount() <= this.depth + 1) {
                return OptionalInt.empty();
            }
            final String named = within.getName(this.depth).toString();
            // Digits, and exactly as many as a scope writes. A sign is what the count alone lets
            // through: -999 is four characters and spells itself back. Counted, it would draw a row
            // that refuses the very text clicking it types.
            if (named.length() != this.digits || !named.chars().allMatch(Character::isDigit)) {
                return OptionalInt.empty();
            }
            final int value = Integer.parseInt(named);
            return named.equals(this.spelling(value)) ? OptionalInt.of(value) : OptionalInt.empty();
        }

        /**
         * How a scope writes this folder's name for a given number.
         *
         * <p>The two sit together rather than one beside each constant, because what matters is
         * that they differ and how. {@code CullScopeSelector} resolves a year by its own digits and
         * a month padded to a fixed width, and these are those two rules read back.
         *
         * @param value int the number the folder names
         * @return {@link String} the name a scope would resolve
         */
        private String spelling(final int value) {
            return switch (this) {
                case YEAR -> String.valueOf(value);
                case MONTH -> Numerals.padded(value, MONTH_DIGITS);
            };
        }
    }

    /**
     * One year's counts while the walk that fills them is still going.
     */
    private static final class Counts {

        private final Map<Integer, Tally> byMonth = new TreeMap<>();
        private final Tally whole = new Tally();

        /**
         * Counts one file toward its year.
         *
         * @param kind {@link MediaType} what it is
         */
        private void add(final MediaType kind) {
            this.whole.add(kind);
        }

        /**
         * Counts one file toward the month it sits in as well.
         *
         * @param kind {@link MediaType} what it is
         * @param month int the month it sits in
         */
        private void addToMonth(final MediaType kind, final int month) {
            this.byMonth.computeIfAbsent(month, _ -> new Tally()).add(kind);
        }

        /**
         * These counts as the row a caller reads.
         *
         * @param year int the year they were gathered under
         * @return {@link YearRow} the finished row
         */
        private YearRow asRow(final int year) {
            final List<MonthRow> months = new ArrayList<>();
            this.byMonth.forEach((month, held) -> months.add(new MonthRow(month, held.photos, held.videos)));
            return new YearRow(year, this.whole.photos, this.whole.videos, months);
        }
    }

    /**
     * Photos and videos counted separately, for a year or for one of its months.
     */
    private static final class Tally {

        private int photos;
        private int videos;

        /**
         * Counts one file.
         *
         * @param kind {@link MediaType} what it is
         */
        private void add(final MediaType kind) {
            switch (kind) {
                case PHOTO -> this.photos++;
                case VIDEO -> this.videos++;
            }
        }
    }
}
