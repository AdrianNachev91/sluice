package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.cull.CullScope;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Turns the numbers and lists behind a run into the words the dashboard says them in.
 *
 * <p>Static and holding nothing. Every face of the dashboard counts something, and a count reads the
 * same way whichever face it appears on. The launcher's cards, the progress bars and the result card
 * all separate their thousands through {@link #grouped}.
 */
final class RunWords {

    // Nominal lengths, for reading an age aloud rather than for arithmetic anyone relies on.
    private static final int DAYS_IN_A_MONTH = 30;
    private static final int DAYS_IN_TWO_MONTHS = 60;
    private static final int DAYS_IN_A_YEAR = 365;
    private static final int DAYS_IN_TWO_YEARS = 730;

    private RunWords() {
    }

    /**
     * A year scope as a sentence names it.
     *
     * @param scope {@link CullScope.Year} the scope, or null where its own tag names no year
     * @return {@link String} what to call it
     */
    static String spelledScope(final CullScope.@Nullable Year scope) {
        if (scope == null) {
            return "another timeframe";
        }
        return scope.months() == null
                ? String.valueOf(scope.year())
                : namedMonths(scope.months()) + " " + scope.year();
    }

    /**
     * A count with its noun, singular or plural to match.
     *
     * @param count int how many
     * @param one {@link String} the noun for one
     * @param many {@link String} the noun for anything else
     * @return {@link String} the count and its noun
     */
    static String counted(final int count, final String one, final String many) {
        return grouped(count) + " " + (count == 1 ? one : many);
    }

    /**
     * How long ago something happened, at the coarsest honest precision.
     *
     * <p>A thing nobody could stat is dated as the epoch. That reads here as not known, rather than
     * as a date in 1970.
     *
     * @param since {@link Instant} when it happened
     * @return {@link String} how long ago, as a reader would say it
     */
    static String howLongAgo(final Instant since) {
        if (Instant.EPOCH.equals(since)) {
            return "not known";
        }
        final Duration ago = Duration.between(since, Instant.now());
        if (ago.toHours() < 1) {
            return "less than an hour ago";
        }
        if (ago.toDays() < 1) {
            return counted((int) ago.toHours(), "hour", "hours") + " ago";
        }
        if (ago.toDays() < DAYS_IN_TWO_MONTHS) {
            return counted((int) ago.toDays(), "day", "days") + " ago";
        }
        if (ago.toDays() < DAYS_IN_TWO_YEARS) {
            return counted((int) (ago.toDays() / DAYS_IN_A_MONTH), "month", "months") + " ago";
        }
        return counted((int) (ago.toDays() / DAYS_IN_A_YEAR), "year", "years") + " ago";
    }

    /**
     * A number with thousands separated, the way somebody reading it would write it.
     *
     * @param value long the number
     * @return {@link String} the number written out
     */
    static String grouped(final long value) {
        return String.format(Locale.UK, "%,d", value);
    }

    /**
     * A token count at the precision the estimate actually has.
     *
     * <p>Rounded to two figures. The arithmetic behind it is an average over past runs, multiplied
     * by a montage count. A figure written out to the last token would claim a precision no part of
     * it holds.
     *
     * @param tokens long the estimated tokens
     * @return {@link String} the figure to show
     */
    static String rounded(final long tokens) {
        final long scale = (long) Math.pow(10, Math.max(0, String.valueOf(tokens).length() - 2));
        return grouped(Math.round((double) tokens / scale) * scale);
    }

    /**
     * A size as somebody would say it.
     *
     * @param bytes long the size on disk
     * @return {@link String} the size written out
     */
    static String sized(final long bytes) {
        final String[] units = {"bytes", "KB", "MB", "GB", "TB"};
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return unit == 0
                ? grouped(bytes) + " bytes"
                : String.format(Locale.UK, "%.1f %s", size, units[unit]);
    }

    /**
     * What a timeframe or a folder says it holds.
     *
     * @param photos int the photos in it
     * @param videos int the videos in it
     * @return {@link String} the counts written out
     */
    static String held(final int photos, final int videos) {
        if (videos == 0) {
            return counted(photos, "photo", "photos");
        }
        if (photos == 0) {
            return counted(videos, "video", "videos");
        }
        return counted(photos, "photo", "photos") + " and " + counted(videos, "video", "videos");
    }

    /**
     * What to call a chosen folder or file on screen.
     *
     * <p>A drive root has no leaf name, so it answers with itself. Somebody importing a camera card
     * picks one, and {@code E:\} is what they call it.
     *
     * @param chosen {@link Path}
     * @return {@link String}
     */
    static String named(final Path chosen) {
        final Path leaf = chosen.getFileName();
        return String.valueOf(leaf == null ? chosen : leaf);
    }

    /**
     * Several things read as one phrase.
     *
     * @param names a {@link List} of {@link String} at least one thing
     * @return {@link String} the things joined the way a sentence joins them
     */
    static String listed(final List<String> names) {
        if (names.size() == 1) {
            return names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    /**
     * A month list written out, in order and with each named once.
     *
     * <p>Not what was typed. A run is expanded into the months it covers and the whole list is
     * sorted, so {@code 11,6,8} comes back as {@code 6,8,11}. What a refusal quotes is therefore the
     * set the text came to rather than the text itself, which is the thing being refused.
     *
     * @param months a {@link List} of {@link Integer} the months
     * @return {@link String} the months joined by commas
     */
    static String joined(final List<Integer> months) {
        return months.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    /**
     * A run of months as a sentence names them.
     *
     * @param months a {@link List} of {@link Integer} the months, in order
     * @return {@link String} the months written out
     */
    static String namedMonths(final List<Integer> months) {
        return listed(months.stream().map(RunWords::monthName).toList());
    }

    /**
     * One month by name.
     *
     * @param month int the month, 1 to 12
     * @return {@link String} its name
     */
    static String monthName(final int month) {
        return Month.of(month).getDisplayName(TextStyle.FULL, Locale.UK);
    }
}
