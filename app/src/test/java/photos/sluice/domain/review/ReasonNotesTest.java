package photos.sluice.domain.review;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReasonNotesTest {

    @Test
    void aLineDatedToTheDayReadsBackAsThatDay() {
        final String line = ReasonNotes.line("IMG_1.jpg", LocalDate.of(2019, 6, 14), false, "too small to sift");

        assertThat(line).isEqualTo("IMG_1.jpg (2019-06-14) - too small to sift");
        assertThat(ReasonNotes.datesIn(List.of(line))).containsExactly(entry("IMG_1.jpg", 2019, 6, 14));
    }

    @Test
    void aLineDatedToTheMonthReadsBackAsTheFirstOfIt() {
        final String line = ReasonNotes.line("IMG_1.jpg", YearMonth.of(2019, 6), "a photo of a screen");

        assertThat(line).isEqualTo("IMG_1.jpg (2019-06) - a photo of a screen");
        assertThat(ReasonNotes.datesIn(List.of(line))).containsExactly(entry("IMG_1.jpg", 2019, 6, 1));
    }

    @Test
    void aDateOffTheFilesOwnTimestampSaysSoAndStillReadsBack() {
        final String line = ReasonNotes.line("IMG_1.jpg", LocalDate.of(2019, 6, 14), true, "too small to sift");

        assertThat(line).isEqualTo("IMG_1.jpg (2019-06-14, low confidence date) - too small to sift");
        assertThat(ReasonNotes.datesIn(List.of(line))).containsExactly(entry("IMG_1.jpg", 2019, 6, 14));
    }

    @Test
    void aLineWrittenBeforeDatesWereRecordedDatesNothing() {
        final String line = ReasonNotes.line("IMG_1.jpg", "no date could be read");

        assertThat(line).isEqualTo("IMG_1.jpg - no date could be read");
        assertThat(ReasonNotes.datesIn(List.of(line))).isEmpty();
    }

    @Test
    void aCollisionSuffixInTheNameIsNotMistakenForADate() {
        final String line = ReasonNotes.line("IMG_1 (2).jpg", LocalDate.of(2019, 6, 14), false, "junk");

        assertThat(ReasonNotes.datesIn(List.of(line))).containsExactly(entry("IMG_1 (2).jpg", 2019, 6, 14));
    }

    @Test
    void aDateNobodyCouldHaveWrittenDatesNothingRatherThanThrowing() {
        assertThat(ReasonNotes.datesIn(List.of("IMG_1.jpg (2019-13-40) - junk"))).isEmpty();
    }

    @Test
    void aLineAReaderMangledPastRecognitionDatesNothing() {
        assertThat(ReasonNotes.datesIn(List.of("", "   ", "no separator here", "IMG_1.jpg (2019-0"))).isEmpty();
    }

    @Test
    void aReasonHoldingItsOwnSeparatorStaysWithTheReason() {
        assertThat(ReasonNotes.datesIn(List.of("IMG_1.jpg (2019-06) - a screen - blurred")))
                .containsExactly(entry("IMG_1.jpg", 2019, 6, 1));
    }

    // The shard contract asks only that a reason not be blank, so a line break can reach here.
    @Test
    void aReasonHoldingALineBreakStillLeavesOneLineForOnePhoto() {
        final String line = ReasonNotes.line("IMG_1.jpg", YearMonth.of(2019, 6), "a screen\r\nand blurred\ntoo");

        assertThat(line.lines()).hasSize(1);
        assertThat(line).isEqualTo("IMG_1.jpg (2019-06) - a screen and blurred too");
        assertThat(ReasonNotes.datesIn(List.of(line))).containsExactly(entry("IMG_1.jpg", 2019, 6, 1));
    }

    // A reason is free text a culling agent wrote, so it can hold the shape a dated line has.
    @Test
    void aReasonShapedLikeADateCannotTakeTheDateOffTheNameBeforeIt() {
        assertThat(ReasonNotes.datesIn(List.of("IMG_1.jpg (2019-06) - shot of a screen (2020-01) - blurred")))
                .containsExactly(entry("IMG_1.jpg", 2019, 6, 1));
    }

    @Test
    void theLaterOfTwoLinesAboutOnePhotoIsTheOneRead() {
        assertThat(ReasonNotes.datesIn(List.of("IMG_1.jpg (2019-06) - junk", "IMG_1.jpg (2021-03) - junk")))
                .containsExactly(entry("IMG_1.jpg", 2021, 3, 1));
    }

    @Test
    void aPhotoIsListedWhicheverShapeItsLineWasWrittenIn() {
        assertThat(ReasonNotes.lists(List.of("IMG_1.jpg - junk"), "IMG_1.jpg")).isTrue();
        assertThat(ReasonNotes.lists(List.of("IMG_1.jpg (2019-06) - junk"), "IMG_1.jpg")).isTrue();
        assertThat(ReasonNotes.lists(List.of("IMG_2.jpg (2019-06) - junk"), "IMG_1.jpg")).isFalse();
    }

    // Windows names a duplicate this way, so it is a real filename rather than an invented one.
    @Test
    void aFilenameHoldingTheSeparatorIsStillFoundInTheNote() {
        assertThat(ReasonNotes.lists(List.of("IMG_1 - Copy.jpg - junk"), "IMG_1 - Copy.jpg")).isTrue();
        assertThat(ReasonNotes.lists(List.of("IMG_1 - Copy.jpg (2019-06) - junk"), "IMG_1 - Copy.jpg")).isTrue();
    }

    @Test
    void onlyADatedLineTellsAShorterNameFromTheStartOfALongerOne() {
        assertThat(ReasonNotes.lists(List.of("IMG_1 - Copy.jpg (2019-06) - junk"), "IMG_1")).isFalse();
        assertThat(ReasonNotes.lists(List.of("IMG_1 - Copy.jpg - junk"), "IMG_1")).isTrue();
    }

    @Test
    void aNameThatMerelyStartsAnotherIsNotTakenForIt() {
        assertThat(ReasonNotes.lists(List.of("IMG_12.jpg (2019-06) - junk"), "IMG_1.jpg")).isFalse();
        assertThat(ReasonNotes.lists(List.of("IMG_1.jpg extra - junk"), "IMG_1.jpg")).isFalse();
    }

    @Test
    void bothOfTheShapesThisAppWritesAreItsOwn() {
        assertThat(ReasonNotes.isReasonNote("_reasons.txt")).isTrue();
        assertThat(ReasonNotes.isReasonNote("IMG_1.jpg.txt")).isTrue();
        assertThat(ReasonNotes.isReasonNote("clip.MOV.txt")).isTrue();
        assertThat(ReasonNotes.isReasonNote("IMG_1 - Copy.jpg.txt")).isTrue();
    }

    @Test
    void aTextFileNamedForNoPhotoBelongsToWhoeverPutItThere() {
        assertThat(ReasonNotes.isReasonNote("my-own-notes.txt")).isFalse();
        assertThat(ReasonNotes.isReasonNote("holiday.notes.txt")).isFalse();
        assertThat(ReasonNotes.isReasonNote("notes.docx")).isFalse();
        assertThat(ReasonNotes.isReasonNote("IMG_1.jpg")).isFalse();
        assertThat(ReasonNotes.isReasonNote(".txt")).isFalse();
    }

    private static Map.Entry<String, LocalDate> entry(final String name, final int year,
                                                      final int month, final int day) {
        return Map.entry(name, LocalDate.of(year, month, day));
    }
}
