package photos.sluice.domain.sift;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiftCategoryTest {

    @Test
    void carriesNameAndDescription() {
        final var category = SiftCategory.of("receipts", "Paper receipts and invoices");

        assertThat(category.name()).isEqualTo("receipts");
        assertThat(category.description()).isEqualTo("Paper receipts and invoices");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> SiftCategory.of("  ", "Paper receipts and invoices"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsANameThatCannotBecomeAFolder() {
        assertThatThrownBy(() -> SiftCategory.of("../Photos", "Escapes the review root"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case");
        assertThatThrownBy(() -> SiftCategory.of("Receipts", "Capitalised"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case");
        assertThatThrownBy(() -> SiftCategory.of("con", "A Windows device"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved by the operating system");
    }

    @Test
    void rejectsABlankDescription() {
        assertThatThrownBy(() -> SiftCategory.of("receipts", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receipts");
    }

    @Test
    void ofDefaultsToNoExamplesAndSwitchedOn() {
        final var category = SiftCategory.of("receipts", "Paper receipts and invoices");

        assertThat(category.examples()).isEmpty();
        assertThat(category.enabled()).isTrue();
    }

    @Test
    void dropsBlankExamplesAndTrimsTheRest() {
        // A YAML list written with a bare "-" binds that entry as null, so the fixture carries one
        // beside the blank string.
        //noinspection NullableProblems
        final var category = new SiftCategory("food", "Meals",
                Arrays.asList("  restaurant plates ", "   ", null, "home dinners"), Boolean.TRUE);

        assertThat(category.examples()).containsExactly("restaurant plates", "home dinners");
    }

    @Test
    void aCardNobodySwitchedOffIsOn() {
        //noinspection DataFlowIssue
        final var category = new SiftCategory("receipts", "Paperwork", null, null);

        assertThat(category.enabled()).isTrue();
        assertThat(category.examples()).isEmpty();
    }

    @Test
    void aCardSwitchedOffStaysOff() {
        final var category = new SiftCategory("receipts", "Paperwork", List.of(), Boolean.FALSE);

        assertThat(category.enabled()).isFalse();
    }

    @Test
    void rejectsADescriptionPastItsCeiling() {
        final String tooLong = "x".repeat(SiftCategory.maxDescriptionLength() + 1);

        assertThatThrownBy(() -> SiftCategory.of("receipts", tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description longer");
        assertThat(SiftCategory.of("receipts", "x".repeat(SiftCategory.maxDescriptionLength())).description())
                .hasSize(SiftCategory.maxDescriptionLength());
    }

    @Test
    void rejectsAnExamplePastItsCeiling() {
        final List<String> tooLong = List.of("x".repeat(SiftCategory.maxExample() + 1));

        assertThatThrownBy(() -> new SiftCategory("receipts", "Paperwork", tooLong, Boolean.TRUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("example longer");
    }

    @Test
    void rejectsMoreExamplesThanACardMayCarry() {
        final List<String> tooMany = IntStream.rangeClosed(0, SiftCategory.maxExamples())
                .mapToObj(Integer::toString).toList();

        assertThatThrownBy(() -> new SiftCategory("receipts", "Paperwork", tooMany, Boolean.TRUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than the");
    }

    @Test
    void rejectsNulls() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> SiftCategory.of(null, "Paper receipts and invoices"))
                .isInstanceOf(IllegalArgumentException.class);
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> SiftCategory.of("receipts", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
