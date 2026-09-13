package photos.sluice.domain.sift;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.Numerals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The set of ways a sift run's photos can be selected: a specific year (optionally narrowed to
 * months), or the N oldest-by-mtime files present.
 *
 * <p>Months may be an explicit, non-contiguous set. A contiguous
 * {@link photos.sluice.domain.model.MonthRange} (used by
 * {@link photos.sluice.domain.model.SortScope}/{@link photos.sluice.domain.commit.CommitScope})
 * cannot express skipping a single outlier month out of an otherwise-batched year. {@link OldestN}
 * orders by raw filesystem mtime, not a resolved date. This differs from
 * {@link photos.sluice.domain.model.SortScope.OldestN}, which follows the dating-resolution chain.
 */
public sealed interface SiftScope {

    /**
     * Selects every candidate dated to a specific year, optionally narrowed to an explicit set of
     * months.
     */
    record Year(int year, @Nullable List<Integer> months) implements SiftScope {
        /**
         * Defensively copies the months list.
         *
         * @param year int the scope's year
         * @param months a {@link List} of {@link Integer} specific months to include, or null for the whole year
         */
        public Year {
            months = months == null ? null : List.copyOf(months);
        }

        /**
         * Whether this scope and another cover any of the same months.
         *
         * <p>Null months mean the whole year, so such a scope meets anything else in that year.
         *
         * <p>Two scopes can overlap without sharing a tag, which is what makes this worth asking.
         * A prep dir is claimed by its exact tag, so the whole of 2019 and June 2019 are unrelated
         * keys. A run over each sheets and pays for June twice.
         *
         * @param other {@link Year} the scope to compare against, or null where none is known
         * @return boolean true where the two share at least one month
         */
        public boolean overlaps(final @Nullable Year other) {
            if (other == null || other.year != this.year) {
                return false;
            }
            if (this.months == null || other.months == null) {
                return true;
            }
            return this.months.stream().anyMatch(other.months::contains);
        }
    }

    /**
     * Selects the {@code n} oldest candidates by mtime, irrespective of which year or month they
     * fall in.
     */
    record OldestN(int n) implements SiftScope {
    }

    /**
     * The on-disk tag identifying this scope's prep dir (logs/sift-prep/<tag>/), which is also the
     * string {@code PrepDir.scope()} carries.
     *
     * @param scope {@link SiftScope} the sift scope to tag
     * @return {@link String} the scope's on-disk tag
     */
    static String tag(final SiftScope scope) {
        return switch (scope) {
            case Year(final int year, final List<Integer> months) -> yearTag(year, months);
            case OldestN(final int n) -> "oldest-" + n;
        };
    }

    /**
     * The tag a prep dir on disk was sifted under, which is its own folder name.
     *
     * <p>A path with no name component at all answers the whole path, so that a caller sweeping
     * whatever it found on disk always gets a string back.
     *
     * @param prepDir {@link Path} the prep directory to name
     * @return {@link String} its tag
     */
    static String tagOf(final Path prepDir) {
        final Path name = prepDir.getFileName();
        return name == null ? prepDir.toString() : name.toString();
    }

    /**
     * The year scope a tag stands for, or null where it names no year.
     *
     * <p>The inverse of {@link #tag} over its {@link Year} case, and it sits beside it so the two
     * cannot drift apart.
     *
     * <p>{@link OldestN} answers null, because a count of files names no timeframe. So does anything
     * else found in the sift-prep root, including a folder somebody made by hand.
     *
     * @param tag {@link String} a prep dir's own folder name
     * @return {@link Year} the scope it stands for, months null for a whole year, or null where the
     *         tag names no year
     */
    static @Nullable Year yearScopeOf(final String tag) {
        final String[] parts = tag.split("-");
        if (parts.length == 0 || parts[0].length() != 4 || isNotDigits(parts[0])) {
            return null;
        }
        final List<Integer> months = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() != 2 || isNotDigits(parts[i])) {
                return null;
            }
            final int month = Integer.parseInt(parts[i]);
            // A tag is built from real months, so anything outside the calendar was not built by
            // this app.
            if (month < 1 || month > 12) {
                return null;
            }
            months.add(month);
        }
        return new Year(Integer.parseInt(parts[0]), months.isEmpty() ? null : months);
    }

    /**
     * Whether any character is not a digit.
     *
     * @param part {@link String} one hyphen-separated piece of a tag
     * @return boolean true where something in it is not a digit
     */
    private static boolean isNotDigits(final String part) {
        return !part.chars().allMatch(Character::isDigit);
    }

    /**
     * Builds the tag suffix for a year scope, including months when narrowed.
     *
     * @param year int the scope's year
     * @param months a {@link List} of {@link Integer} specific months to include, or null for the whole year
     * @return {@link String} the year (and optional month suffix) tag
     */
    private static String yearTag(final int year, final @Nullable List<Integer> months) {
        if (months == null) {
            return String.valueOf(year);
        }
        final String monthSuffix = months.stream()
                .distinct()
                .sorted()
                .map(month -> Numerals.padded(month, 2))
                .collect(Collectors.joining("-"));
        return year + "-" + monthSuffix;
    }
}
