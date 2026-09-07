package photos.sluice.adapter;

import java.util.List;

/**
 * The month text both surfaces read, and what each of them owes for it.
 *
 * <p>Two parsers read the same thing. The desktop's scope field and the command line's months
 * option each have their own, each answering in its own type. Neither can see the other, both
 * being package-private inside their own adapter. So no compiler and no shared call holds them to
 * each other. This table is what holds them, read by a test on each side.
 *
 * <p>A trailing comma is why it exists. The desktop dropped it and started a paid sift of a whole
 * year; the command line refused it. Nothing related the two until something did.
 *
 * <p>A span inside a list, {@code 6-8,11}, is in the table because both sides read it the same way.
 * Whether a verb can then act on a set with a gap in it is a separate question. It is asked where
 * the answer differs per verb, rather than in the reading.
 */
public final class ScopeMonthCases {

    private ScopeMonthCases() {
    }

    /**
     * One month text, and the months it names where it names any.
     *
     * @param typed {@link String} the month text, as it is typed after the year
     * @param months a {@link List} of {@link Integer} the months it comes to, empty where both
     *         surfaces must refuse it
     */
    public record Case(String typed, List<Integer> months) {

        /**
         * Whether both surfaces have to refuse this text.
         *
         * @return boolean true where neither may read months out of it
         */
        public boolean refused() {
            return this.months.isEmpty();
        }
    }

    /**
     * Every case both parsers are held to.
     *
     * @return a {@link List} of {@link Case} the cases
     */
    public static List<Case> all() {
        return List.of(
                new Case("6", List.of(6)),
                new Case("6,8", List.of(6, 8)),
                new Case("6-8", List.of(6, 7, 8)),
                new Case("8,6", List.of(6, 8)),
                new Case("6-8,11", List.of(6, 7, 8, 11)),
                new Case("1,3-5,12", List.of(1, 3, 4, 5, 12)),
                // The four a stray keystroke produces. Each one starts paid work if a parser reads
                // it as the year alone.
                new Case(",", List.of()),
                new Case("6,", List.of()),
                new Case(",6", List.of()),
                new Case("6,,7", List.of()),
                new Case("0", List.of()),
                new Case("13", List.of()),
                new Case("8-6", List.of()),
                new Case("six", List.of()));
    }
}
