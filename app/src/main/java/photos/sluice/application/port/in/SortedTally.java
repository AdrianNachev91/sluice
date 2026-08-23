package photos.sluice.application.port.in;

import java.util.List;

/**
 * What is staged in Sorted, one row per year, newest year first.
 *
 * <p>Exact rather than estimated. A sort files a photo under its year, so the year directories are
 * the answer and no dating pass is needed to read them.
 *
 * @param years a {@link List} of {@link YearRow} one row per year holding anything, newest first
 */
public record SortedTally(List<YearRow> years) {

    /**
     * Defensively copies the rows.
     *
     * @param years a {@link List} of {@link YearRow} one row per year holding anything
     */
    public SortedTally {
        years = List.copyOf(years);
    }

    /**
     * How many photos and videos are staged for one year, and how they fall across its months.
     *
     * <p>The two counts stay apart because only photos can be sifted. A row whose videos are its
     * whole count has nothing for a vision provider to look at.
     *
     * <p>{@code months} can come to less than the year's own counts, and the two are not the same
     * question. A sort writes every file into a month folder, so ordinarily they agree. A file
     * dropped straight into the year folder by hand counts toward the year and belongs to no
     * month. So the difference is real rather than a rounding of the same number.
     *
     * <p>What reaches such a file differs by run. A sift over the whole year reads the year folder
     * itself, so it finds it; narrowed to months, it does not. A move takes a file by the year and
     * month its own path spells, and this one spells no month, so only a move over everything
     * staged reaches it.
     *
     * @param year int the year
     * @param photos int how many photos are staged anywhere under it
     * @param videos int how many videos are staged anywhere under it
     * @param months a {@link List} of {@link MonthRow} its months holding anything, in order
     */
    public record YearRow(int year, int photos, int videos, List<MonthRow> months) {

        /**
         * Defensively copies the month rows.
         *
         * @param year int the year
         * @param photos int how many photos are staged anywhere under it
         * @param videos int how many videos are staged anywhere under it
         * @param months a {@link List} of {@link MonthRow} its months holding anything, in order
         */
        public YearRow {
            months = List.copyOf(months);
        }

        /**
         * Everything staged under this year.
         *
         * @return int the photos and videos together
         */
        public int total() {
            return this.photos + this.videos;
        }

        /**
         * How many photos a run narrowed to these months would take.
         *
         * <p>A month named twice is counted once, and a month holding nothing contributes nothing,
         * which is what scanning its folder would find.
         *
         * @param wanted a {@link List} of {@link Integer} the months to include
         * @return int the photos in range
         */
        public int photosIn(final List<Integer> wanted) {
            return this.months.stream()
                    .filter(month -> wanted.contains(month.month()))
                    .mapToInt(MonthRow::photos)
                    .sum();
        }
    }

    /**
     * How much of a year sits in one of its months.
     *
     * <p>Both counts, because two questions are asked of a month. Sizing a sift narrowed to it needs
     * the photos, since a sift looks at no videos. Saying what a move narrowed to it would take
     * needs both, since a move takes everything filed under the month.
     *
     * @param month int the month, 1 through 12
     * @param photos int how many photos are staged under it
     * @param videos int how many videos are staged under it
     */
    public record MonthRow(int month, int photos, int videos) {
    }
}
