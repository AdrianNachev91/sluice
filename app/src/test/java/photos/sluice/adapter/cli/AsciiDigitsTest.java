package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsciiDigitsTest {

    @Test
    void theTenDigitsAreTheOnesItAccepts() {
        assertThat(AsciiDigits.isAllDigits("0123456789")).isTrue();
        assertThat(AsciiDigits.isAllDigits("2019")).isTrue();
    }

    @Test
    void digitsFromAnotherScriptAreNotAccepted() {
        assertThat(AsciiDigits.isAllDigits("٠٠١٩")).isFalse();
        assertThat(AsciiDigits.isAllDigits("٦")).isFalse();
        assertThat(AsciiDigits.isAllDigits("१२")).isFalse();
    }

    @Test
    void anythingThatIsNotADigitIsNotAccepted() {
        assertThat(AsciiDigits.isAllDigits("20x9")).isFalse();
        assertThat(AsciiDigits.isAllDigits("summer")).isFalse();
        assertThat(AsciiDigits.isAllDigits(" 2019")).isFalse();
        assertThat(AsciiDigits.isAllDigits("-1")).isFalse();
    }

    @Test
    void anEmptyValueIsNotDigits() {
        assertThat(AsciiDigits.isAllDigits("")).isFalse();
    }
}
