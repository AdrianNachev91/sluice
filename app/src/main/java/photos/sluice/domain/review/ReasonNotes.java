package photos.sluice.domain.review;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.scan.MediaTypeDetector;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The note Sluice leaves beside photos it set aside, and the one place its lines are written and
 * read back. One line per photo, naming the file, when it was taken, and why it is here.
 *
 * <p>A line's date is what lets a rescue put the photo back under the month it came from. A reader
 * is invited to open the folder and weed it. A reason can be free text a culling agent wrote. A
 * line read back is input from outside on both counts. Anything that does not match exactly yields
 * no date at all, and never a guessed one.
 *
 * <p>Lines written before dates existed carry a name and a reason only. Both shapes are read, and
 * the two readings are deliberately different.
 */
public final class ReasonNotes {

    /** What the note file in a folder is called. */
    public static final String FILE_NAME = "_reasons.txt";

    /**
     * What every note file in a folder ends in.
     *
     * <p>A near-copy group is named for the photo it kept rather than {@link #FILE_NAME}, so a
     * reader of a folder's lines has to take them all.
     */
    public static final String SUFFIX = ".txt";

    private static final String SEPARATOR = " - ";

    // Sits inside the date's own brackets, on a line whose date came off the file's timestamp
    // rather than off the photo. Only a sort can write one, and only for a photo it set aside as
    // too small: nothing else here holds a date it doubts.
    private static final String LOW_CONFIDENCE = ", low confidence date";

    // Every shape a line break takes, since a reason travels here as JSON out of a culling agent.
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    // The name reluctantly, so the date is the first parenthesised one that a separator follows.
    // A reason carrying its own " (yyyy-MM) - " is read that way only on a line the writer left
    // undated. What comes back is then a name no file on disk has.
    private static final Pattern DATED_LINE = Pattern.compile(
            "^(.*?) \\((\\d{4})-(\\d{2})(?:-(\\d{2}))?(?:" + Pattern.quote(LOW_CONFIDENCE) + ")?\\)"
                    + Pattern.quote(SEPARATOR));

    /**
     * Prevents instantiation of this static utility class.
     */
    private ReasonNotes() {
    }

    /**
     * Whether a filename is one this app wrote about the photos in a folder.
     *
     * <p>Two shapes. One is {@link #FILE_NAME} itself. The other is a near-copy group's own note,
     * named for the photo that group kept, so {@code IMG_1.jpg.txt} is the note about
     * {@code IMG_1.jpg}.
     *
     * <p>The photo a note is named for does not have to still be beside it. A reader who deleted the
     * kept copy by hand would otherwise leave its note behind for good, since nothing else ever
     * removes one.
     *
     * <p>Every other {@code .txt} answers false. These are folders people are told to open and weed,
     * so one of them may have put their own notes in it.
     *
     * @param fileName {@link String} a file's own name, with no folder above it
     * @return boolean true where this app wrote it
     */
    public static boolean isReasonNote(final String fileName) {
        if (FILE_NAME.equals(fileName)) {
            return true;
        }
        if (!fileName.endsWith(SUFFIX)) {
            return false;
        }
        final String named = fileName.substring(0, fileName.length() - SUFFIX.length());
        final int dot = named.lastIndexOf('.');
        return dot >= 0 && MediaTypeDetector.isRecognizedExtension(named.substring(dot + 1));
    }

    /**
     * One photo's line, dated to the day.
     *
     * @param landedName {@link String} the name the file was actually filed under
     * @param taken {@link LocalDate} when the photo was taken
     * @param lowConfidence boolean true where that date came off the file's timestamp
     * @param reason {@link String} why the photo is in this folder
     * @return {@link String} the line
     */
    public static String line(final String landedName, final LocalDate taken, final boolean lowConfidence,
                              final String reason) {
        return landedName + " (" + taken + (lowConfidence ? LOW_CONFIDENCE : "") + ")" + SEPARATOR
                + onOneLine(reason);
    }

    /**
     * One photo's line, dated to the month.
     *
     * @param landedName {@link String} the name the file was actually filed under
     * @param taken {@link YearMonth} the month the photo was filed under
     * @param reason {@link String} why the photo is in this folder
     * @return {@link String} the line
     */
    public static String line(final String landedName, final YearMonth taken, final String reason) {
        return landedName + " (" + taken + ")" + SEPARATOR + onOneLine(reason);
    }

    /**
     * One photo's line, where nothing could date it.
     *
     * @param landedName {@link String} the name the file was actually filed under
     * @param reason {@link String} why the photo is in this folder
     * @return {@link String} the line
     */
    public static String line(final String landedName, final String reason) {
        return landedName + SEPARATOR + onOneLine(reason);
    }

    /**
     * When each photo a folder's note dates was taken, by the name it was filed under.
     *
     * <p>Only a dated line answers. Three kinds are absent rather than present with nothing. A line
     * naming no date, one whose digits name no real date, and one that is no note line at all.
     *
     * <p>Two lines naming one photo answer with the later, appends being in the order they were
     * written. That is a hand-edited note or a name a folder took twice, and either way the newer
     * line is the app's own most recent answer.
     *
     * @param lines a {@link List} of {@link String} every line the note holds
     * @return a {@link Map} of {@link String} to {@link LocalDate}, dates by filed name
     */
    public static Map<String, LocalDate> datesIn(final List<String> lines) {
        final Map<String, LocalDate> dates = new LinkedHashMap<>();
        for (final String line : lines) {
            final Matcher dated = DATED_LINE.matcher(line);
            if (dated.find()) {
                final LocalDate taken = dateOf(dated.group(2), dated.group(3), dated.group(4));
                if (taken != null) {
                    dates.put(dated.group(1), taken);
                }
            }
        }
        return dates;
    }

    /**
     * Whether a folder's note already has a line about one photo.
     *
     * <p>A dated line answers exactly, its date marking where its name ends.
     * {@code IMG - Copy.jpg (2019-06) - blurry} is about that photo, not about one called
     * {@code IMG}. An undated line has no such mark, both readings of it being lines this class
     * could have written. So a name ending at a separator inside a longer one reads as listed.
     *
     * <p>Neither reading is decidable from the line alone, so the two errors are weighed instead.
     * A false yes leaves the photo out of the note. A false no appends a second line about a photo
     * already listed, which costs a reader one confusing line and no photo anything. So this
     * answers yes only where a reading is certain, and takes the duplicate everywhere else.
     *
     * @param lines a {@link List} of {@link String} every line the note holds
     * @param name {@link String} the name the photo was filed under
     * @return boolean true where some line is about that photo
     */
    public static boolean lists(final List<String> lines, final String name) {
        return lines.stream().anyMatch(line -> nameIn(line)
                .map(name::equals)
                .orElseGet(() -> line.startsWith(name + SEPARATOR)));
    }

    /**
     * A reason flattened to fit the one line its photo gets.
     *
     * <p>A reason is free text a culling agent wrote, and the shard contract asks only that it not
     * be blank.
     *
     * @param reason {@link String} why the photo is in this folder, as its writer gave it
     * @return {@link String} the same words, on one line
     */
    private static String onOneLine(final String reason) {
        return LINE_BREAK.matcher(reason).replaceAll(" ").strip();
    }

    /**
     * The name a line dates, reading it the way a writer here would have written it.
     *
     * @param line {@link String} the line
     * @return an {@link Optional} {@link String}, empty where no such reading exists
     */
    private static Optional<String> nameIn(final String line) {
        final Matcher dated = DATED_LINE.matcher(line);
        return dated.find() ? Optional.of(dated.group(1)) : Optional.empty();
    }

    /**
     * The date three matched groups name, the day being absent on a line dated to the month.
     *
     * @param year {@link String} four digits
     * @param month {@link String} two digits, which the pattern does not bound to 1 through 12
     * @param day {@link String} two digits, or null where the line names none
     * @return {@link LocalDate} that date, or null where those digits name no real one
     */
    private static @Nullable LocalDate dateOf(final String year, final String month, final @Nullable String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month),
                    day == null ? 1 : Integer.parseInt(day));
        } catch (final DateTimeException _) {
            return null;
        }
    }
}
