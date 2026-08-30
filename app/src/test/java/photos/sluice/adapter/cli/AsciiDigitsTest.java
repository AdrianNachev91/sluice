package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsciiDigitsTest {

    @Test
    void theTenDigitsAreTheOnesItAccepts() {
        assertThat(AsciiDigits.only("0123456789")).isTrue();
        assertThat(AsciiDigits.only("2019")).isTrue();
    }

    @Test
    void digitsFromAnotherScriptAreNotAccepted() {
        assertThat(AsciiDigits.only("٠٠١٩")).isFalse();
        assertThat(AsciiDigits.only("٦")).isFalse();
        assertThat(AsciiDigits.only("१२")).isFalse();
    }

    @Test
    void anythingThatIsNotADigitIsNotAccepted() {
        assertThat(AsciiDigits.only("20x9")).isFalse();
        assertThat(AsciiDigits.only("summer")).isFalse();
        assertThat(AsciiDigits.only(" 2019")).isFalse();
        assertThat(AsciiDigits.only("-1")).isFalse();
    }

    @Test
    void anEmptyValueIsNotDigits() {
        assertThat(AsciiDigits.only("")).isFalse();
    }
}
